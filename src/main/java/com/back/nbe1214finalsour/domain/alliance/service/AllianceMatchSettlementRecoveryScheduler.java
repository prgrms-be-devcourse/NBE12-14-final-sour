package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlementStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "alliance.settlement.recovery", name = "enabled", havingValue = "true")
public class AllianceMatchSettlementRecoveryScheduler {
    private final AllianceMatchSettlementRepository settlementRepository;
    private final AllianceMatchSettlementOrchestrator orchestrator;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void retryIncompleteSettlements() {
        List<Long> ids = settlementRepository.findIncompleteIds(
                List.of(AllianceMatchSettlementStatus.PENDING, AllianceMatchSettlementStatus.APPLIED),
                PageRequest.of(0, 100));
        for (Long matchId : ids) {
            try {
                orchestrator.settleAndComplete(matchId);
            } catch (RuntimeException e) {
                log.warn("연합 대결 정산 재시도 실패: matchId={}", matchId, e);
            }
        }
    }
}
