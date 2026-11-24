package com.back.nbe1214finalsour.domain.university.scheduler;

import com.back.nbe1214finalsour.domain.university.service.RankSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 00:00 KST 순위 복사본 (SCRUM-184 R6). 테스트처럼 스케줄링을 끄면 등록하지 않는다 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true")
public class RankSnapshotScheduler {

    private final RankSnapshotService rankSnapshotService;

    // 다른 서버가 먼저 만들었으면 날짜가 이미 오늘이라 건너뛴다
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void daily() {
        run();
    }

    // 자정에 서버가 없었던 날(첫 배포·장애)은 시작 시각을 기준으로 삼는다
    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        run();
    }

    private void run() {
        try {
            if (rankSnapshotService.snapshotIfMissing()) {
                log.info("[RankSnapshot] 순위 복사본 생성");
            }
        } catch (RuntimeException e) {
            // 실패하면 날짜가 갱신되지 않아 오늘은 변동을 숨긴다. 다음 서버 시작이나 다음 자정에 다시 만든다
            log.error("[RankSnapshot] 순위 복사본 생성 실패", e);
        }
    }
}
