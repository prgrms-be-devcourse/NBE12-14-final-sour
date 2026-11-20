package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "alliance.scoring", name = "enabled", havingValue = "true")
public class AllianceMatchScoringScheduler {
    private final AllianceMatchRepository matches;
    private final AllianceMatchScorePreparation preparation;
    private final AllianceMatchScoreLedger ledger;

    public AllianceMatchScoringScheduler(AllianceMatchRepository matches, AllianceMatchScorePreparation preparation,
                                         AllianceMatchScoreLedger ledger) {
        this.matches = matches;
        this.preparation = preparation;
        this.ledger = ledger;
    }

    @Scheduled(initialDelayString = "${alliance.scoring.prepare-delay:PT2S}",
            fixedDelayString = "${alliance.scoring.prepare-delay:PT2S}")
    public void prepareStartedMatches() {
        for (Long matchId : matches.findMatchesAwaitingScorePreparation(OffsetDateTime.now())) {
            try {
                if (!preparation.prepare(matchId)) log.debug("대결 점수 준비 미완료: matchId={}", matchId);
            } catch (RuntimeException e) {
                log.warn("대결 점수 준비 실패: matchId={}", matchId, e);
            }
        }
    }

    @Scheduled(initialDelayString = "${alliance.scoring.finalize-delay:PT5S}",
            fixedDelayString = "${alliance.scoring.finalize-delay:PT5S}")
    public void finalizeDueMatches() {
        OffsetDateTime now = OffsetDateTime.now();
        long afterId = 0;
        List<Long> ids;
        // A missing ledger on an older match must not prevent other matches from finalizing.
        do {
            ids = matches.findMatchesAwaitingScoreFinalization(now, afterId);
            for (Long matchId : ids) {
                try {
                    if (!ledger.finalizeScore(matchId)) log.warn("대결 점수 확정 불가: matchId={}", matchId);
                } catch (RuntimeException e) {
                    log.warn("대결 점수 확정 실패: matchId={}", matchId, e);
                }
                afterId = matchId;
            }
        } while (!ids.isEmpty());
    }
}
