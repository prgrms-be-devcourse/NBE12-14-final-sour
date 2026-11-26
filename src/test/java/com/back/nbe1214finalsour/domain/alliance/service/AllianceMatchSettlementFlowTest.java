package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementFlowTest {
    private static final Long MATCH_ID = 99L;
    private static final Long ALLIANCE_A_ID = 10L;
    private static final Long ALLIANCE_B_ID = 20L;

    @Mock AllianceRepository allianceRepository;
    @Mock AllianceMatchRepository matchRepository;
    @Mock AllianceMatchSettlementRepository settlementRepository;
    @InjectMocks AllianceMatchSettlementCompletionService completionService;

    @Test
    @DisplayName("APPLIED 정산만 대결 종료·연합 복구·COMPLETED로 변경한다")
    void completeApplied_restoresBothAlliances() {
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);
        Alliance a = Alliance.create("A", 72, startedAt.minusHours(1));
        Alliance b = Alliance.create("B", 72, startedAt.minusHours(1));
        a.startMatch(startedAt);
        b.startMatch(startedAt);
        AllianceMatch match = AllianceMatch.request(10L, 20L, 6, startedAt.plusHours(1));
        match.accept(startedAt);
        AllianceMatchSettlement record = record();
        record.markApplied();
        when(matchRepository.findPairById(99L)).thenReturn(Optional.of(new AllianceMatchPair(10L, 20L)));
        when(allianceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(a));
        when(allianceRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(b));
        when(matchRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(match));
        when(settlementRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));

        completionService.completeApplied(99L);

        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.FINISHED);
        assertThat(match.getWinnerAllianceId()).isEqualTo(10L);
        assertThat(a.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        assertThat(b.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        InOrder order = inOrder(matchRepository, allianceRepository, settlementRepository);
        order.verify(matchRepository).findPairById(99L);
        order.verify(allianceRepository).findByIdForUpdate(10L);
        order.verify(allianceRepository).findByIdForUpdate(20L);
        order.verify(matchRepository).findByIdForUpdate(99L);
        order.verify(settlementRepository).findByIdForUpdate(99L);
    }

    @Test
    @DisplayName("PENDING 정산이면 대결 상태를 변경하지 않는다")
    void completeApplied_rejectsPending() {
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);
        Alliance a = Alliance.create("A", 72, startedAt.minusHours(1));
        Alliance b = Alliance.create("B", 72, startedAt.minusHours(1));
        a.startMatch(startedAt);
        b.startMatch(startedAt);
        AllianceMatch match = AllianceMatch.request(10L, 20L, 6, startedAt.plusHours(1));
        match.accept(startedAt);
        when(matchRepository.findPairById(99L)).thenReturn(Optional.of(new AllianceMatchPair(10L, 20L)));
        when(allianceRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(a));
        when(allianceRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(b));
        when(matchRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(match));
        when(settlementRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(record()));

        assertThatThrownBy(() -> completionService.completeApplied(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis 적용이 확인되지 않은 정산입니다.");
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(a.getStatus()).isEqualTo(AllianceStatus.IN_MATCH);
    }

    private static AllianceMatchSettlement record() {
        return AllianceMatchSettlement.prepare(99L, 300L, 200L,
                1L, 2L, 3L, 4L, 120L, 180L, 80L, 120L);
    }

    // ===== 정산 완료의 조회 실패·상태 오류·재시도 검증 =====

    @Test
    @DisplayName("null, 0, 음수 대결 ID는 저장소 조회 전에 거부")
    void rejectsInvalidMatchIdsBeforeRepositoryLookup() {
        for (Long id : new Long[] { null, 0L, -1L }) {
            assertThatThrownBy(() -> completionService.completeApplied(id))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("대결 ID가 올바르지 않습니다.");
        }
        verifyNoInteractions(matchRepository, allianceRepository, settlementRepository);
    }

    @Test
    @DisplayName("대결 참가 연합 쌍을 찾을 수 없으면 거부")
    void rejectsMissingPair() {
        when(matchRepository.findPairById(MATCH_ID)).thenReturn(Optional.empty());

        assertFailure("대결을 찾을 수 없습니다.");
        verifyNoInteractions(allianceRepository, settlementRepository);
    }

    @Test
    @DisplayName("첫 번째 연합이 없으면 거부")
    void rejectsMissingFirstAlliance() {
        stubPair(ALLIANCE_A_ID, ALLIANCE_B_ID);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_A_ID)).thenReturn(Optional.empty());

        assertFailure("연합을 찾을 수 없습니다.");
        verify(allianceRepository, never()).findByIdForUpdate(ALLIANCE_B_ID);
    }

    @Test
    @DisplayName("두 번째 연합이 없으면 거부")
    void rejectsMissingSecondAlliance() {
        stubPair(ALLIANCE_A_ID, ALLIANCE_B_ID);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_A_ID))
                .thenReturn(Optional.of(mock(Alliance.class)));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_B_ID)).thenReturn(Optional.empty());

        assertFailure("연합을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("잠금 조회 시 대결이 없으면 거부")
    void rejectsMissingMatchAfterAllianceLocks() {
        stubPairAndAlliances(ALLIANCE_A_ID, ALLIANCE_B_ID);
        when(matchRepository.findByIdForUpdate(MATCH_ID)).thenReturn(Optional.empty());

        assertFailure("대결을 찾을 수 없습니다.");
        verifyNoInteractions(settlementRepository);
    }

    @Test
    @DisplayName("대결의 A 연합이 잠금 전 조회한 연합 쌍과 다르면 거부")
    void rejectsMismatchedAllianceA() {
        stubUpToMatch(ALLIANCE_A_ID, ALLIANCE_B_ID, requestedMatch(30L, ALLIANCE_B_ID));

        assertFailure("대결 참가 연합이 일치하지 않습니다.");
        verifyNoInteractions(settlementRepository);
    }

    @Test
    @DisplayName("대결의 B 연합이 잠금 전 조회한 연합 쌍과 다르면 거부")
    void rejectsMismatchedAllianceB() {
        stubUpToMatch(ALLIANCE_A_ID, ALLIANCE_B_ID, requestedMatch(ALLIANCE_A_ID, 30L));

        assertFailure("대결 참가 연합이 일치하지 않습니다.");
        verifyNoInteractions(settlementRepository);
    }

    @Test
    @DisplayName("정산 이력이 없으면 대결 종료하지 않음")
    void rejectsMissingSettlement() {
        stubUpToMatch(ALLIANCE_A_ID, ALLIANCE_B_ID,
                requestedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID));
        when(settlementRepository.findByIdForUpdate(MATCH_ID)).thenReturn(Optional.empty());

        assertFailure("정산 이력을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("COMPLETED 정산의 대결이 FINISHED가 아니면 불일치 거부")
    void rejectsCompletedSettlementWhenMatchNotFinished() {
        stubFull(requestedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID), completedSettlement());

        assertFailure("정산 완료 상태와 대결 상태가 일치하지 않습니다.");
    }

    @Test
    @DisplayName("COMPLETED 정산과 대결 A 점수가 다르면 거부")
    void rejectsCompletedSettlementWithChangedAScore() {
        stubFull(finishedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID, 301L, 200L), completedSettlement());

        assertFailure("정산 완료 상태와 대결 상태가 일치하지 않습니다.");
    }

    @Test
    @DisplayName("COMPLETED 정산과 대결 B 점수가 다르면 거부")
    void rejectsCompletedSettlementWithChangedBScore() {
        stubFull(finishedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID, 300L, 201L), completedSettlement());

        assertFailure("정산 완료 상태와 대결 상태가 일치하지 않습니다.");
    }

    @Test
    @DisplayName("COMPLETED 정산과 FINISHED 대결의 점수가 같으면 안전하게 재시도 종료")
    void completedSettlementIsIdempotent() {
        AllianceMatch match = finishedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID, 300L, 200L);
        AllianceMatchSettlement settlement = completedSettlement();
        Alliance first = mock(Alliance.class);
        Alliance second = mock(Alliance.class);
        stubFull(ALLIANCE_A_ID, ALLIANCE_B_ID, match, settlement, first, second);

        completionService.completeApplied(MATCH_ID);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.FINISHED);
        assertThat(settlement.getStatus()).isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
        verify(first, never()).finishMatch();
        verify(second, never()).finishMatch();
    }

    @Test
    @DisplayName("APPLIED 이외의 정산은 완료 처리하지 않음")
    void rejectsPendingSettlement() {
        AllianceMatchSettlement pending = settlement();
        stubFull(inProgressMatch(ALLIANCE_A_ID, ALLIANCE_B_ID), pending);

        assertFailure("Redis 적용이 확인되지 않은 정산입니다.");
        assertThat(pending.getStatus()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
    }

    @Test
    @DisplayName("APPLIED여도 진행 중이지 않은 대결이면 종료하지 않음")
    void rejectsAppliedSettlementWhenMatchNotInProgress() {
        AllianceMatchSettlement applied = appliedSettlement();
        stubFull(requestedMatch(ALLIANCE_A_ID, ALLIANCE_B_ID), applied);

        assertFailure("진행 중인 대결만 종료할 수 있습니다.");
        assertThat(applied.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("대결 종료 시각이 아직 오지 않았다면 완료 처리하지 않음")
    void rejectsCompletionBeforeEndTime() {
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(1);
        AllianceMatch match = AllianceMatch.request(ALLIANCE_A_ID, ALLIANCE_B_ID,
                6, startedAt.plusHours(1));
        match.accept(startedAt);
        AllianceMatchSettlement applied = appliedSettlement();
        stubFull(match, applied);

        assertFailure("대결 종료 시각이 지나지 않았습니다.");
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(applied.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("연합 ID 역순이어도 ID 오름차순 잠금 후 대결 정상 완료")
    void completesWhenPairOrderIsReversed() {
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);
        Alliance first = inMatchAlliance("first", startedAt);
        Alliance second = inMatchAlliance("second", startedAt);
        AllianceMatch match = AllianceMatch.request(ALLIANCE_B_ID, ALLIANCE_A_ID,
                6, startedAt.plusHours(1));
        match.accept(startedAt);
        AllianceMatchSettlement applied = appliedSettlement();
        stubFull(ALLIANCE_B_ID, ALLIANCE_A_ID, match, applied, first, second);

        completionService.completeApplied(MATCH_ID);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.FINISHED);
        assertThat(match.getWinnerAllianceId()).isEqualTo(ALLIANCE_B_ID);
        assertThat(first.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        assertThat(second.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        assertThat(applied.getStatus()).isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
        var order = inOrder(matchRepository, allianceRepository, settlementRepository);
        order.verify(matchRepository).findPairById(MATCH_ID);
        order.verify(allianceRepository).findByIdForUpdate(ALLIANCE_A_ID);
        order.verify(allianceRepository).findByIdForUpdate(ALLIANCE_B_ID);
        order.verify(matchRepository).findByIdForUpdate(MATCH_ID);
        order.verify(settlementRepository).findByIdForUpdate(MATCH_ID);
    }

    private void assertFailure(String message) {
        assertThatThrownBy(() -> completionService.completeApplied(MATCH_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message);
    }

    private void stubPair(Long allianceAId, Long allianceBId) {
        when(matchRepository.findPairById(MATCH_ID))
                .thenReturn(Optional.of(new AllianceMatchPair(allianceAId, allianceBId)));
    }

    private void stubPairAndAlliances(Long allianceAId, Long allianceBId) {
        stubPair(allianceAId, allianceBId);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_A_ID))
                .thenReturn(Optional.of(mock(Alliance.class)));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_B_ID))
                .thenReturn(Optional.of(mock(Alliance.class)));
    }

    private void stubUpToMatch(Long allianceAId, Long allianceBId, AllianceMatch match) {
        stubPairAndAlliances(allianceAId, allianceBId);
        when(matchRepository.findByIdForUpdate(MATCH_ID)).thenReturn(Optional.of(match));
    }

    private void stubFull(AllianceMatch match, AllianceMatchSettlement settlement) {
        stubFull(ALLIANCE_A_ID, ALLIANCE_B_ID, match, settlement,
                mock(Alliance.class), mock(Alliance.class));
    }

    private void stubFull(Long allianceAId, Long allianceBId, AllianceMatch match,
                          AllianceMatchSettlement settlement, Alliance first, Alliance second) {
        stubPair(allianceAId, allianceBId);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_A_ID)).thenReturn(Optional.of(first));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_B_ID)).thenReturn(Optional.of(second));
        when(matchRepository.findByIdForUpdate(MATCH_ID)).thenReturn(Optional.of(match));
        when(settlementRepository.findByIdForUpdate(MATCH_ID)).thenReturn(Optional.of(settlement));
    }

    private static AllianceMatch requestedMatch(Long allianceAId, Long allianceBId) {
        OffsetDateTime now = OffsetDateTime.now();
        return AllianceMatch.request(allianceAId, allianceBId, 6, now.plusHours(1));
    }

    private static AllianceMatch inProgressMatch(Long allianceAId, Long allianceBId) {
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);
        AllianceMatch match = AllianceMatch.request(allianceAId, allianceBId,
                6, startedAt.plusHours(1));
        match.accept(startedAt);
        return match;
    }

    private static AllianceMatch finishedMatch(Long allianceAId, Long allianceBId,
                                                long aScore, long bScore) {
        AllianceMatch match = inProgressMatch(allianceAId, allianceBId);
        match.finish(OffsetDateTime.now(), aScore, bScore);
        return match;
    }

    private static AllianceMatchSettlement settlement() {
        return AllianceMatchSettlement.prepare(MATCH_ID, 300L, 200L,
                1L, 2L, 3L, 4L, 120L, 180L, 80L, 120L);
    }

    private static AllianceMatchSettlement appliedSettlement() {
        AllianceMatchSettlement settlement = settlement();
        settlement.markApplied();
        return settlement;
    }

    private static AllianceMatchSettlement completedSettlement() {
        AllianceMatchSettlement settlement = appliedSettlement();
        settlement.markCompleted();
        return settlement;
    }

    private static Alliance inMatchAlliance(String name, OffsetDateTime startedAt) {
        Alliance alliance = Alliance.create(name, 72, startedAt.minusHours(1));
        alliance.startMatch(startedAt);
        return alliance;
    }
}
