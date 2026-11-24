package com.back.nbe1214finalsour.domain.alliance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementOrchestratorTest {
    @Mock AllianceMatchSettlementApplicationService application;
    @Mock AllianceMatchSettlementCompletionService completion;
    @InjectMocks AllianceMatchSettlementOrchestrator orchestrator;

    @Test
    @DisplayName("적용 이후 종료 순서")
    void settleAndComplete_executesInOrder() {
        orchestrator.settleAndComplete(99L);
        InOrder order = inOrder(application, completion);
        order.verify(application).applyPending(99L);
        order.verify(completion).completeApplied(99L);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("Redis 적용 실패 시 종료 중단")
    void settleAndComplete_stopsOnFailure() {
        doThrow(new IllegalStateException("Redis 실패")).when(application).applyPending(99L);
        assertThatThrownBy(() -> orchestrator.settleAndComplete(99L))
                .hasMessage("Redis 실패");
        verifyNoInteractions(completion);
    }

    @Test
    @DisplayName("종료 실패 후 재시도")
    void settleAndComplete_retries() {
        doThrow(new IllegalStateException("DB 실패")).doNothing()
                .when(completion).completeApplied(99L);
        assertThatThrownBy(() -> orchestrator.settleAndComplete(99L))
                .hasMessage("DB 실패");
        orchestrator.settleAndComplete(99L);
        verify(application, times(2)).applyPending(99L);
        verify(completion, times(2)).completeApplied(99L);
    }
}
