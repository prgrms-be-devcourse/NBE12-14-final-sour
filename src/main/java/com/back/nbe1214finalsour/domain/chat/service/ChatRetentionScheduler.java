package com.back.nbe1214finalsour.domain.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** scheduling.enabled가 true일 때 scheduling.chat-retention-delay 주기로 정리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true")
public class ChatRetentionScheduler {
    private final ChatRetentionService service;

    @Scheduled(initialDelayString = "${scheduling.chat-retention-delay}", fixedDelayString = "${scheduling.chat-retention-delay}")
    public void run() {
        try {
            service.deleteUniversityMessages();
        } catch (Exception e) {
            log.error("학교 채팅 보관 기간 정리 실패, 다음 회차에 재시도", e);
        }
        try {
            service.deleteAllianceMessages();
        } catch (Exception e) {
            log.error("연합 채팅 보관 기간 정리 실패, 다음 회차에 재시도", e);
        }
    }
}
