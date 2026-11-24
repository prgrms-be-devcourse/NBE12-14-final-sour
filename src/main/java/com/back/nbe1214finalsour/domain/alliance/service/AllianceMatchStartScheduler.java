package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AllianceMatchStartScheduler {
    private final AllianceMatchRepository repository;
    private final AllianceMatchService service;

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT10S")
    public void startDueMatches() {
        for (Long matchId : repository.findDueScheduledIds(OffsetDateTime.now())) {
            try {
                service.startScheduledMatch(matchId);
            } catch (RuntimeException e) {
                log.warn("예약 대결 시작 실패: matchId={}", matchId, e);
            }
        }
    }
}
