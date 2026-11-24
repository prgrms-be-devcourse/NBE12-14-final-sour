package com.back.nbe1214finalsour.global.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} 작업용 전용 스레드 풀.
 *
 * <p>여기서 {@code Executor} 빈을 만들면 Spring Boot는 기본 {@code applicationTaskExecutor}를 만들지 않는다. 기본
 * 실행기를 쓰는 다른 기능이 영향을 받지 않도록 {@code application.yaml}에 {@code spring.task.execution.mode: force}를
 * 둔다 ({@code SupportAnswerMailIntegrationTest}가 확인한다).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

    /** 문의 답변 메일 ({@code SupportAnswerMailListener}) */
    public static final String SUPPORT_MAIL_EXECUTOR = "supportMailExecutor";

    /**
     * SMTP가 느려도 다른 작업을 막지 않도록 작은 풀과 제한된 대기열을 쓴다. 대기열이 가득 차면 그 메일은 버리고 로그를
     * 남긴다. 거부 예외를 던지면 이미 커밋된 답변 요청이 500이 되기 때문이다.
     */
    @Bean(name = SUPPORT_MAIL_EXECUTOR)
    public ThreadPoolTaskExecutor supportMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("support-mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("문의 답변 메일 대기열이 가득 차 발송을 버림 queue={}", pool.getQueue().size()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
