package com.back.nbe1214finalsour.domain.chat.websocket;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * 세션을 {@link ErrorFrameFlushingSessionDecorator}로 감싸는 STOMP 핸들러 (GitHub #148, SCRUM-245).
 * 보내기 시간·버퍼 한도는 Spring이 {@code chat.websocket.*} 설정으로 이 핸들러에 넣은 값을 그대로 쓴다.
 * Spring 이슈 #37328이 고쳐지면 지운다.
 */
public class ErrorFrameFlushingSubProtocolWebSocketHandler extends SubProtocolWebSocketHandler {

    public ErrorFrameFlushingSubProtocolWebSocketHandler(MessageChannel clientInboundChannel,
                                                         SubscribableChannel clientOutboundChannel) {
        super(clientInboundChannel, clientOutboundChannel);
    }

    @Override
    protected WebSocketSession decorateSession(WebSocketSession session) {
        return new ErrorFrameFlushingSessionDecorator(session, getSendTimeLimit(), getSendBufferSizeLimit());
    }
}
