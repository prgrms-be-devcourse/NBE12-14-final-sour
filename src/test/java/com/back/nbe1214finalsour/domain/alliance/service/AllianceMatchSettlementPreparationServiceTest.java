package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementPreparationServiceTest {
    @Mock AllianceRepository allianceRepository;
    @Mock AllianceMatchRepository matchRepository;
    @Mock AllianceUniversityRepository memberRepository;
    @Mock AllianceMatchSettlementRepository settlementRepository;
    @InjectMocks AllianceMatchSettlementPreparationService service;

    private final AllianceMatchSettlementSnapshot snapshot = new AllianceMatchSettlementSnapshot(
            300L, 200L, 11L, 12L, 21L, 22L, 120L, 180L, 80L, 120L);

    private void givenMatch() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(7);
        Alliance a = Alliance.create("A", 72, start.minusHours(1));
        Alliance b = Alliance.create("B", 72, start.minusHours(1));
        a.startMatch(start);
        b.startMatch(start);
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, start.plusHours(1));
        match.accept(start);
        givenLockedMatch(match, a, b);
    }

    // 테스트 대상이 락을 모두 획득한 뒤의 조건을 검증할 때 사용하는 최소한의 공통 설정.
    private void givenLockedMatch(AllianceMatch match, Alliance first, Alliance second) {
        when(matchRepository.findPairById(99L))
                .thenReturn(Optional.of(new AllianceMatchPair(1L, 2L)));
        when(allianceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(allianceRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(second));
        when(matchRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(match));
    }

    // 시간 및 상태 조건만 따로 변경하기 위해 사용하는 대결 모의 객체.
    private AllianceMatch mockedMatch() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(1L);
        when(match.getAllianceBId()).thenReturn(2L);
        return match;
    }

    private static AllianceMatchSettlement existingSettlement() {
        return AllianceMatchSettlement.prepare(99L, 300L, 200L,
                11L, 12L, 21L, 22L, 120L, 180L, 80L, 120L);
    }

    private void givenAcceptedMembers() {
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(1L, 11L, 101L),
                        AllianceUniversity.createFounder(1L, 12L, 102L)));
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                2L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(2L, 21L, 103L),
                        AllianceUniversity.createFounder(2L, 22L, 104L)));
    }

    @Test
    @DisplayName("대결 참가 학교가 일치하면 PENDING 스냅샷 생성")
    void prepare_acceptsConfirmedSnapshot() {
        givenMatch();
        givenAcceptedMembers();
        when(settlementRepository.saveAndFlush(any(AllianceMatchSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.prepare(99L, snapshot);

        assertThat(saved.getStatus()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
        assertThat(saved.getBUniversity2Id()).isEqualTo(22L);
        verify(settlementRepository).saveAndFlush(any(AllianceMatchSettlement.class));
    }

    @Test
    @DisplayName("실제 연합 소속 학교와 다르면 지급 준비를 거부")
    void prepare_rejectsUnknownSchool() {
        givenMatch();
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(1L, 11L, 101L),
                        AllianceUniversity.createFounder(1L, 13L, 102L)));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 학교");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("연합의 승인된 학교가 두 개가 아니면 정산을 거부")
    void prepare_rejectsIncompleteMembers() {
        givenMatch();
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(AllianceUniversity.createFounder(1L, 11L, 101L)));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 학교");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("B연합 학교 명단이 스냅샷과 다르면 정산을 거부")
    void prepare_rejectsWrongSecondAllianceMembers() {
        givenMatch();
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(1L, 11L, 101L),
                        AllianceUniversity.createFounder(1L, 12L, 102L)));
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                2L, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(2L, 21L, 103L),
                        AllianceUniversity.createFounder(2L, 23L, 104L)));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 학교");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("기존 스냅샷과 점수가 다르면 변경 불가")
    void prepare_rejectsModifiedSnapshot() {
        givenMatch();
        when(settlementRepository.findById(99L)).thenReturn(Optional.of(existingSettlement()));
        var changed = new AllianceMatchSettlementSnapshot(301L, 200L,
                11L, 12L, 21L, 22L, 120L, 180L, 80L, 120L);

        assertThatThrownBy(() -> service.prepare(99L, changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스냅샷");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동일 스냅샷 재요청은 기존 정산 객체를 그대로 반환하며 다시 저장하지 않는다")
    void prepare_returnsExistingSettlementForSameSnapshot() {
        givenMatch();
        AllianceMatchSettlement existing = existingSettlement();
        when(settlementRepository.findById(99L)).thenReturn(Optional.of(existing));

        AllianceMatchSettlement result = service.prepare(99L, snapshot);

        assertThat(result).isSameAs(existing);
        verify(settlementRepository, never()).saveAndFlush(any());
        verifyNoInteractions(memberRepository);
    }

    // 기존 A 점수 변경 테스트는 유지하고, 나머지 9개 비교 항목을 하나씩 변경한다.
    static Stream<Arguments> changedSnapshots() {
        return Stream.of(
                Arguments.of("B 점수", new AllianceMatchSettlementSnapshot(
                        300L, 201L, 11L, 12L, 21L, 22L, 120L, 180L, 80L, 120L)),
                Arguments.of("A 학교1", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 13L, 12L, 21L, 22L, 120L, 180L, 80L, 120L)),
                Arguments.of("A 학교2", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 13L, 21L, 22L, 120L, 180L, 80L, 120L)),
                Arguments.of("B 학교1", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 23L, 22L, 120L, 180L, 80L, 120L)),
                Arguments.of("B 학교2", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 21L, 23L, 120L, 180L, 80L, 120L)),
                Arguments.of("A 학교1 기여도", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 21L, 22L, 121L, 180L, 80L, 120L)),
                Arguments.of("A 학교2 기여도", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 21L, 22L, 120L, 181L, 80L, 120L)),
                Arguments.of("B 학교1 기여도", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 21L, 22L, 120L, 180L, 81L, 120L)),
                Arguments.of("B 학교2 기여도", new AllianceMatchSettlementSnapshot(
                        300L, 200L, 11L, 12L, 21L, 22L, 120L, 180L, 80L, 121L))
        );
    }

    @ParameterizedTest(name = "{index}: {0} 변경 불가")
    @MethodSource("changedSnapshots")
    void prepare_rejectsEachChangedSnapshotField(String changedField,
                                                   AllianceMatchSettlementSnapshot changed) {
        givenMatch();
        when(settlementRepository.findById(99L)).thenReturn(Optional.of(existingSettlement()));

        assertThatThrownBy(() -> service.prepare(99L, changed))
                .as(changedField)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스냅샷");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("대결 ID가 null이면 저장 작업 없이 거부")
    void prepare_rejectsNullMatchId() {
        assertThatThrownBy(() -> service.prepare(null, snapshot))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(allianceRepository, matchRepository, memberRepository, settlementRepository);
    }

    @ParameterizedTest(name = "대결 ID {0} 거부")
    @ValueSource(longs = {0L, -1L})
    void prepare_rejectsNonPositiveMatchId(long invalidId) {
        assertThatThrownBy(() -> service.prepare(invalidId, snapshot))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(allianceRepository, matchRepository, memberRepository, settlementRepository);
    }

    @Test
    @DisplayName("스냅샷이 null이면 저장 작업 없이 거부")
    void prepare_rejectsNullSnapshot() {
        assertThatThrownBy(() -> service.prepare(99L, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(allianceRepository, matchRepository, memberRepository, settlementRepository);
    }

    @Test
    @DisplayName("대결 쌍이 조회되지 않으면 예외 발생")
    void prepare_rejectsMissingMatchPair() {
        when(matchRepository.findPairById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("대결을 찾을 수 없습니다");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("첫 번째 연합이 조회되지 않으면 예외 발생")
    void prepare_rejectsMissingFirstAlliance() {
        when(matchRepository.findPairById(99L))
                .thenReturn(Optional.of(new AllianceMatchPair(1L, 2L)));
        when(allianceRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연합을 찾을 수 없습니다");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("두 번째 연합이 조회되지 않으면 예외 발생")
    void prepare_rejectsMissingSecondAlliance() {
        when(matchRepository.findPairById(99L))
                .thenReturn(Optional.of(new AllianceMatchPair(1L, 2L)));
        when(allianceRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(mock(Alliance.class)));
        when(allianceRepository.findByIdForUpdate(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("연합을 찾을 수 없습니다");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("락 획득 뒤 대결이 조회되지 않으면 예외 발생")
    void prepare_rejectsMissingLockedMatch() {
        when(matchRepository.findPairById(99L))
                .thenReturn(Optional.of(new AllianceMatchPair(1L, 2L)));
        when(allianceRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(mock(Alliance.class)));
        when(allianceRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(mock(Alliance.class)));
        when(matchRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("대결을 찾을 수 없습니다");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("대결의 첫 번째 연합 ID와 조회된 연합 쌍이 다르면 거부")
    void prepare_rejectsMismatchedFirstAllianceId() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(3L);
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 연합");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("대결의 두 번째 연합 ID와 조회된 연합 쌍이 다르면 거부")
    void prepare_rejectsMismatchedSecondAllianceId() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(1L);
        when(match.getAllianceBId()).thenReturn(3L);
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 연합");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("진행 중이 아닌 대결은 정산을 준비할 수 없다")
    void prepare_rejectsNonInProgressMatch() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.REQUESTED);
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행 중 대결");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("삭제된 대결은 정산을 준비할 수 없다")
    void prepare_rejectsDeletedMatch() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.IN_PROGRESS);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now());
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행 중 대결");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("종료 시각이 정해지지 않은 대결은 정산을 준비할 수 없다")
    void prepare_rejectsMatchWithoutEndTime() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.IN_PROGRESS);
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 시각");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("아직 종료 시각이 지나지 않은 대결은 정산을 준비할 수 없다")
    void prepare_rejectsMatchBeforeEndTime() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.IN_PROGRESS);
        when(match.getEndedAt()).thenReturn(OffsetDateTime.now().plusHours(1));
        givenLockedMatch(match, mock(Alliance.class), mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("종료 시각");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("첫 번째 연합이 대결 상태가 아니면 정산을 거부")
    void prepare_rejectsInactiveFirstAlliance() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.IN_PROGRESS);
        when(match.getEndedAt()).thenReturn(OffsetDateTime.now().minusHours(1));
        Alliance first = mock(Alliance.class);
        when(first.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        givenLockedMatch(match, first, mock(Alliance.class));

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("양쪽 연합");
        verify(settlementRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("두 번째 연합이 대결 상태가 아니면 정산을 거부")
    void prepare_rejectsInactiveSecondAlliance() {
        AllianceMatch match = mockedMatch();
        when(match.getStatus()).thenReturn(AllianceMatchStatus.IN_PROGRESS);
        when(match.getEndedAt()).thenReturn(OffsetDateTime.now().minusHours(1));
        Alliance first = mock(Alliance.class);
        Alliance second = mock(Alliance.class);
        when(first.getStatus()).thenReturn(AllianceStatus.IN_MATCH);
        when(second.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        givenLockedMatch(match, first, second);

        assertThatThrownBy(() -> service.prepare(99L, snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("양쪽 연합");
        verify(settlementRepository, never()).saveAndFlush(any());
    }
}
