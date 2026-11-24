package com.back.nbe1214finalsour.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 작업(점수 배치, 연합 만료 등)이 Boot 기본 스케줄러({@code taskScheduler}, 스레드 이름 {@code scheduling-})에서 도는지 확인한다.
 *
 * <p>STOMP 브로커({@code @EnableWebSocketMessageBroker})는 {@code messageBrokerTaskScheduler}를 만든다. TaskScheduler 빈이 생기면
 * Boot는 기본 {@code taskScheduler}를 만들지 않으므로({@code @ConditionalOnMissingBean}), 그대로 두면 예약 작업이 브로커 스레드로 옮겨 간다.
 * {@code ScheduledAnnotationBeanPostProcessor}는 TaskScheduler가 여러 개면 이름이 {@code taskScheduler}인 빈을 쓴다.
 */
@IntegrationTest
class TaskSchedulerIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("예약 작업용 taskScheduler는 Boot 기본 설정(scheduling- 스레드)이다")
    void scheduledTasksUseBootDefaultScheduler() {
        assertThat(this.context.containsBean("taskScheduler")).isTrue();
        ThreadPoolTaskScheduler scheduler = this.context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);

        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
    }
}
