package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 점수 집계 담당의 SnapshotProvider 연결 후에만 활성화.
 * provider 빈이 없는데 auto.enabled=true면 기동 실패 -> 잘못된 0점 정산을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "alliance.settlement.auto", name = "enabled", havingValue = "true")
public class AllianceMatchDueSettlementScheduler {
    private final AllianceMatchRepository matchRepository;
    private final AllianceMatchSettlementRepository settlementRepository;
    private final AllianceMatchSettlementSnapshotProvider snapshotProvider;
    private final AllianceMatchSettlementPreparationService preparationService;
    private final AllianceMatchSettlementOrchestrator orchestrator;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void settleDueMatches() {
        for (Long matchId : matchRepository.findDueInProgressIds(OffsetDateTime.now())) {
            try {
                if (!settlementRepository.existsById(matchId)) {
                    AllianceMatchSettlementSnapshot snapshot = snapshotProvider.loadFinalSnapshot(matchId);
                    if (snapshot == null) {
                        throw new IllegalStateException("최종 대결 점수 스냅샷이 없습니다.");
                    }
                    preparationService.prepare(matchId, snapshot);
                }
                orchestrator.settleAndComplete(matchId);
            } catch (RuntimeException e) {
                log.warn("종료 대상 대결 정산 실패: matchId={}", matchId, e);
            }
        }
    }
}
