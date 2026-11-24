package com.back.nbe1214finalsour.domain.alliance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AllianceMatchSettlementOrchestrator {
    private final AllianceMatchSettlementApplicationService applicationService;
    private final AllianceMatchSettlementCompletionService completionService;

    // @Transactional 금지: 적용/APPLIED 커밋 후 별도 트랜잭션에서 종료/COMPLETED 처리.
    public void settleAndComplete(Long matchId) {
        applicationService.applyPending(matchId);
        completionService.completeApplied(matchId);
    }
}
