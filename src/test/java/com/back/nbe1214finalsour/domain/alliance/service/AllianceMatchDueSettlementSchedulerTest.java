package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchDueSettlementSchedulerTest {
    @Mock AllianceMatchRepository matchRepository;
    @Mock AllianceMatchSettlementRepository settlementRepository;
    @Mock AllianceMatchSettlementSnapshotProvider provider;
    @Mock AllianceMatchSettlementPreparationService preparation;
    @Mock AllianceMatchSettlementOrchestrator orchestrator;
    @InjectMocks AllianceMatchDueSettlementScheduler scheduler;

    @Test
    @DisplayName("새 종료 대상은 스냅샷 준비 후 정산")
    void preparesNewMatch() {
        var snapshot = new AllianceMatchSettlementSnapshot(300L,200L,
                1L,2L,3L,4L,120L,180L,80L,120L);
        when(matchRepository.findDueInProgressIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(99L));
        when(settlementRepository.existsById(99L)).thenReturn(false);
        when(provider.loadFinalSnapshot(99L)).thenReturn(snapshot);
        scheduler.settleDueMatches();
        InOrder order = inOrder(preparation, orchestrator);
        order.verify(preparation).prepare(99L, snapshot);
        order.verify(orchestrator).settleAndComplete(99L);
    }

    @Test
    @DisplayName("이미 이력이 있으면 새 점수 수집 없이 재시도")
    void retriesExistingSnapshot() {
        when(matchRepository.findDueInProgressIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(99L));
        when(settlementRepository.existsById(99L)).thenReturn(true);
        scheduler.settleDueMatches();
        verifyNoInteractions(provider, preparation);
        verify(orchestrator).settleAndComplete(99L);
    }

    @Test
    @DisplayName("점수 제공자가 스냅샷 없으면 지급 차단")
    void preventsMissingSnapshot() {
        when(matchRepository.findDueInProgressIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(99L));
        when(settlementRepository.existsById(99L)).thenReturn(false);
        scheduler.settleDueMatches();
        verifyNoInteractions(preparation, orchestrator);
    }
}
