package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlementStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementRecoverySchedulerTest {
    @Mock AllianceMatchSettlementRepository repository;
    @Mock AllianceMatchSettlementOrchestrator orchestrator;
    @InjectMocks AllianceMatchSettlementRecoveryScheduler scheduler;

    private void givenPending() {
        when(repository.findIncompleteIds(
                List.of(AllianceMatchSettlementStatus.PENDING,
                        AllianceMatchSettlementStatus.APPLIED), PageRequest.of(0,100)))
                .thenReturn(List.of(99L,100L));
    }

    @Test
    @DisplayName("미완료 정산 재시도")
    void retriesPending() {
        givenPending();
        scheduler.retryIncompleteSettlements();
        verify(orchestrator).settleAndComplete(99L);
        verify(orchestrator).settleAndComplete(100L);
    }

    @Test
    @DisplayName("일부 실패해도 다음 대결 계속 진행")
    void continuesAfterFailure() {
        givenPending();
        doThrow(new IllegalStateException("실패")).when(orchestrator).settleAndComplete(99L);
        scheduler.retryIncompleteSettlements();
        verify(orchestrator).settleAndComplete(100L);
    }
}
