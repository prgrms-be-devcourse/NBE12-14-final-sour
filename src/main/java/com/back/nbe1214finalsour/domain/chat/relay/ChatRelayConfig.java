package com.back.nbe1214finalsour.domain.chat.relay;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 서버 간 채팅 전달 (D4, D5). 서버마다 Redis 패턴 {@code chat:univ:*}와 {@code chat:alliance:*}를 구독한다.
 *
 * <p>순서: Redis는 한 구독 연결에 발행 순서대로 보낸다(Redis Pub/Sub 문서 "Subscribers receive the messages in the order
 * that the messages are published"). 컨테이너 기본 실행기({@code SimpleAsyncTaskExecutor})는 메시지마다 새 스레드를 써서
 * 이 순서가 섞이므로(몰려 온 메시지로 재현, {@code ChatRelayIntegrationTest}), 스레드 하나로 차례대로 처리한다. 이후 클라이언트까지의
 * 순서는 브로커의 {@code preservePublishOrder}({@code ChatWebSocketConfig})가 지킨다.
 *
 * <p>{@code Executor} 빈을 더 만들어도 기본 {@code applicationTaskExecutor}는 그대로다 ({@code spring.task.execution.mode: force},
 * {@code AsyncConfig} 참고).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ChatRelayConfig {

    public static final String LISTENER_EXECUTOR = "chatRelayListenerExecutor";

    /** 대기열이 가득 차면(전송이 크게 밀린 상황) 그 메시지는 버리고 로그를 남긴다. 클라이언트는 이전 메시지 조회로 본다 (D22와 같은 원칙) */
    @Bean(name = LISTENER_EXECUTOR)
    public ThreadPoolTaskExecutor chatRelayListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-relay-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10_000);
        executor.setRejectedExecutionHandler((task, pool) ->
                log.error("채팅 전달 대기열이 가득 차 메시지를 버림 queue={}", pool.getQueue().size()));
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer chatRelayListenerContainer(RedisConnectionFactory connectionFactory,
                                                                    ChatRedisSubscriber subscriber,
                                                                    @Qualifier(LISTENER_EXECUTOR) ThreadPoolTaskExecutor executor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(executor);
        container.addMessageListener(subscriber, List.of(new PatternTopic(ChatRedisSubscriber.UNIVERSITY_CHANNEL_PATTERN),
                new PatternTopic(ChatRedisSubscriber.ALLIANCE_CHANNEL_PATTERN)));
        return container;
    }
}
