package com.back.nbe1214finalsour.domain.chat.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * STOMP 브로커 설정 (GitHub #148, SCRUM-245). {@code @EnableWebSocketMessageBroker}가 불러오는 설정과 같고,
 * STOMP 핸들러만 거절 ERROR 프레임을 보낸 뒤 닫는 {@link ErrorFrameFlushingSubProtocolWebSocketHandler}로 바꾼다.
 *
 * <p>Spring 7.0.10에는 핸들러 생성만 바꿀 확장 지점이 없어 {@code subProtocolWebSocketHandler} 빈 메서드를 덮어쓴다.
 * {@link ChatWebSocketConfig}가 {@code @EnableWebSocketMessageBroker} 대신 {@code @Import}로만 불러온다. 둘을 함께 쓰면
 * 같은 빈을 두 번 정의하게 되고, 컴포넌트 스캔과 겹치지 않도록 {@code @Configuration}을 붙이지 않는다.
 *
 * <p>Spring 이슈 <a href="https://github.com/spring-projects/spring-framework/issues/37328">#37328</a>이 고쳐지면
 * 이 클래스를 지우고 {@code @EnableWebSocketMessageBroker}로 되돌린다.
 */
public class ChatBrokerConfiguration extends DelegatingWebSocketMessageBrokerConfiguration {

    @Bean
    @Override
    public WebSocketHandler subProtocolWebSocketHandler(AbstractSubscribableChannel clientInboundChannel,
                                                        AbstractSubscribableChannel clientOutboundChannel) {
        SubProtocolWebSocketHandler handler =
                new ErrorFrameFlushingSubProtocolWebSocketHandler(clientInboundChannel, clientOutboundChannel);
        handler.setPhase(getPhase());
        return handler;
    }
}
