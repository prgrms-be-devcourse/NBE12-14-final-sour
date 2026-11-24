package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class AllianceMatchExpiryScheduler {
    private final AllianceMatchRepository repository;
    private final AllianceMatchService service;

    // 요청 만료 건만 정리. 진행 중 대결의 승패 정산은 별도(4차 작업).
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void cancelExpiredRequests() {
        for (Long matchId : repository.findExpiredRequestIds(OffsetDateTime.now())) {
            try {
                service.cancelExpiredRequest(matchId); // 건별 별도 트랜잭션
            } catch (RuntimeException e) {
                log.warn("대결 신청 만료 처리 실패: matchId={}", matchId, e);
            }
        }
    }
}
