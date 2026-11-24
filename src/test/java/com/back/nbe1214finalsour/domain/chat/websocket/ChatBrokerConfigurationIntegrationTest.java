package com.back.nbe1214finalsour.domain.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.back.nbe1214finalsour.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

/**
 * 연결 센서 (GitHub #148, SCRUM-245): 실제 {@code /ws} 엔드포인트가 거절 ERROR를 보낸 뒤 닫는 세션 데코레이터를 쓰는지 확인한다.
 * 브로커 설정을 {@code @EnableWebSocketMessageBroker}로 되돌리거나 Spring 버전업으로 핸들러 빈이 바뀌면 이 테스트가 알린다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatBrokerConfigurationIntegrationTest {

    @Autowired
    @Qualifier("stompWebSocketHandlerMapping")
    private HandlerMapping stompWebSocketHandlerMapping;

    @Autowired
    private ChatWebSocketProperties properties;

    @Test
    @DisplayName("/ws의 STOMP 핸들러는 거절 ERROR를 보낸 뒤 닫는 세션 데코레이터를 쓰고, chat.websocket 전송 한도를 그대로 쓴다")
    void endpointUsesErrorFrameFlushingDecorator() {
        Object endpoint = ((AbstractUrlHandlerMapping) this.stompWebSocketHandlerMapping).getHandlerMap()
                .get(ChatWebSocketConfig.ENDPOINT);
        assertThat(endpoint).isInstanceOf(WebSocketHttpRequestHandler.class);
        WebSocketHandler handler = WebSocketHandlerDecorator.unwrap(((WebSocketHttpRequestHandler) endpoint).getWebSocketHandler());

        assertThat(handler).isInstanceOf(ErrorFrameFlushingSubProtocolWebSocketHandler.class);
        WebSocketSession session = ((ErrorFrameFlushingSubProtocolWebSocketHandler) handler)
                .decorateSession(mock(WebSocketSession.class));
        assertThat(session).isInstanceOfSatisfying(ErrorFrameFlushingSessionDecorator.class, decorated -> {
            assertThat(decorated.getSendTimeLimit()).isEqualTo(this.properties.sendTimeLimit().toMillis());
            assertThat(decorated.getBufferSizeLimit()).isEqualTo(this.properties.sendBufferSizeLimit().toBytes());
        });
    }
}
