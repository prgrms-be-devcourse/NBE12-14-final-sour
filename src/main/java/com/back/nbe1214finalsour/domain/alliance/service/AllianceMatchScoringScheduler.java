package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "alliance.scoring", name = "enabled", havingValue = "true")
public class AllianceMatchScoringScheduler {
    private final AllianceMatchRepository matches;
    private final AllianceMatchScorePreparation preparation;

    public AllianceMatchScoringScheduler(AllianceMatchRepository matches, AllianceMatchScorePreparation preparation) {
        this.matches = matches;
        this.preparation = preparation;
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
}
