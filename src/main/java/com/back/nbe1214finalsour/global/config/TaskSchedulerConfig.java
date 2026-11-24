package com.back.nbe1214finalsour.global.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 작업용 스케줄러를 Boot 기본값({@code spring.task.scheduling.*}, 스레드 이름 {@code scheduling-})으로 직접 만든다.
 *
 * <p>채팅 STOMP 브로커가 {@code messageBrokerTaskScheduler}를 만들면 Boot는 기본 {@code taskScheduler}를 만들지 않는다
 * ({@code @ConditionalOnMissingBean(TaskScheduler)}). 그러면 점수 배치 같은 예약 작업이 브로커 heartbeat 스레드에서 돈다.
 * 스케줄러가 여러 개일 때 {@code @Scheduled}는 이름이 {@code taskScheduler}인 빈을 쓴다. 확인: {@code TaskSchedulerIntegrationTest}
 */
@Configuration(proxyBeanMethods = false)
public class TaskSchedulerConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
