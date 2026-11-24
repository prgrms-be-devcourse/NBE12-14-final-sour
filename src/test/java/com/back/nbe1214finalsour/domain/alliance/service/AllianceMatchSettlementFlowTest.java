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
}
