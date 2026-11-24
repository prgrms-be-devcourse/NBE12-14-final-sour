package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class AllianceExpirationScheduler {
    private final AllianceRepository allianceRepository;
    private final AllianceMatchRepository matchRepository;
    private final AllianceMatchService matchService;
    private final AllianceExpirationService expirationService;

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void expireDueAlliances() {
        for (Long allianceId : allianceRepository.findDueExpirationIds(OffsetDateTime.now())) {
            try {
                // 만료된 연합의 대기 대결은 양쪽 연합 잠금 후 취소한다.
                for (Long matchId : matchRepository.findRequestedIdsForAlliance(allianceId)) {
                    matchService.cancelRequestedForAllianceExpiry(matchId, allianceId);
                }
                // 진행 중인 대결이 있다면 정산 완료 때까지 스킵한다.
                expirationService.expireIfDue(allianceId);
            } catch (RuntimeException e) {
                log.warn("연합 만료 처리 실패: allianceId={}", allianceId, e);
            }
        }
    }
}
