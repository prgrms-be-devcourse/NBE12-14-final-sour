package com.back.nbe1214finalsour.domain.chat.websocket;

import com.back.nbe1214finalsour.global.security.CorsConfig.CorsProperties;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * 채팅 STOMP 설정 (SCRUM-128, 계획 §3-1).
 *
 * <ul>
 *   <li>연결 {@code /ws} (SockJS 없음). 허용 origin은 HTTP CORS와 같은 {@code cors.allowed-origins}다</li>
 *   <li>구독 {@code /topic/**}, 사용자 큐 {@code /user/queue/**}, 발행 {@code /app/**}. 인증과 목적지 권한은 {@link ChatStompInterceptor}</li>
 *   <li>SimpleBroker heartbeat 10초 (D14, 명세서 §3-7: ALB 유휴 타임아웃 60초보다 짧게)</li>
 *   <li>전송 한도는 {@link ChatWebSocketProperties} (D23)</li>
 *   <li>같은 세션에는 발행 순서대로 보낸다 ({@code preservePublishOrder})</li>
 *   <li>브로커 설정은 {@code @EnableWebSocketMessageBroker} 대신 {@link ChatBrokerConfiguration}을 불러온다.
 *       거절 ERROR 프레임이 사라지지 않게 세션 데코레이터만 바꾼 것이다 (GitHub #148)</li>
 * </ul>
 * 서버 간 전달(Redis Pub/Sub)은 {@code ChatRelayConfig}.
 */
@Configuration
@Import(ChatBrokerConfiguration.class)
@EnableConfigurationProperties(ChatWebSocketProperties.class)
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";

    /** [서버가 보내는 간격, 클라이언트에게 기대하는 간격] 밀리초 */
    static final long[] HEARTBEAT_MILLIS = {10_000, 10_000};

    private final CorsProperties corsProperties;
    private final ChatWebSocketProperties properties;
    private final ChatStompInterceptor chatStompInterceptor;
    private final ChatOutboundInterceptor chatOutboundInterceptor;
    private final StompErrorFrameHandler stompErrorFrameHandler;
    private final TaskScheduler messageBrokerTaskScheduler;

    // messageBrokerTaskScheduler는 이 설정을 바탕으로 만들어지는 빈이라 @Lazy로 순환 참조를 피한다 (Spring 문서의 heartbeat 예시와 같은 방식)
    public ChatWebSocketConfig(CorsProperties corsProperties,
                               ChatWebSocketProperties properties,
                               ChatStompInterceptor chatStompInterceptor,
                               ChatOutboundInterceptor chatOutboundInterceptor,
                               StompErrorFrameHandler stompErrorFrameHandler,
                               @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.corsProperties = corsProperties;
        this.properties = properties;
        this.chatStompInterceptor = chatStompInterceptor;
        this.chatOutboundInterceptor = chatOutboundInterceptor;
        this.stompErrorFrameHandler = stompErrorFrameHandler;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOrigins(this.corsProperties.allowedOrigins().toArray(String[]::new));
        registry.setErrorHandler(this.stompErrorFrameHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // 같은 세션에는 발행 순서대로 보낸다. 기본값(false)이면 clientOutboundChannel 스레드 풀에서 순서가 섞일 수 있다
        registry.setPreservePublishOrder(true);
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(HEARTBEAT_MILLIS)
                .setTaskScheduler(this.messageBrokerTaskScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(this.chatStompInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(this.chatOutboundInterceptor);
    }

    @Bean
    SmartInitializingSingleton alignOutboundInterceptorOrder(
            @Qualifier("clientOutboundChannel") ExecutorSubscribableChannel outbound) {
        // Spring 7.0.10 inserts the broker's ordering callback at index 0 but appends it to
        // the executor chain. Rebuild that chain after broker initialization, before traffic,
        // so its afterMessageHandled still runs when the authorization interceptor drops a frame.
        return () -> outbound.setInterceptors(List.copyOf(outbound.getInterceptors()));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(Math.toIntExact(this.properties.messageSizeLimit().toBytes()))
                .setTimeToFirstMessage(Math.toIntExact(this.properties.timeToFirstMessage().toMillis()))
                .setSendTimeLimit(Math.toIntExact(this.properties.sendTimeLimit().toMillis()))
                .setSendBufferSizeLimit(Math.toIntExact(this.properties.sendBufferSizeLimit().toBytes()));
    }
}
