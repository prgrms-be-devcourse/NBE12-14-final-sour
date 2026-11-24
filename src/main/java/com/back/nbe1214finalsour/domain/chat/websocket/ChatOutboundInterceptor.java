package com.back.nbe1214finalsour.domain.chat.websocket;

import com.back.nbe1214finalsour.domain.chat.dto.ChatErrorResponse;
import com.back.nbe1214finalsour.domain.chat.service.ChatReceiveAuthorization;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** 발행/큐 적재 시점이 아니라 수신 세션의 전송 handler 직전에 검사한다 (SCRUM-195, D2). */
@Component
public class ChatOutboundInterceptor implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChatOutboundInterceptor.class);
    private final ChatReceiveAuthorization authorization;
    private final SimpMessagingTemplate messaging;
    private final Map<String, Recipient> recipients = new ConcurrentHashMap<>();

    public ChatOutboundInterceptor(ChatReceiveAuthorization authorization, @Lazy SimpMessagingTemplate messaging) {
        this.authorization = authorization;
        this.messaging = messaging;
    }

    @EventListener
    public void connected(SessionConnectedEvent event) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId != null && event.getUser() instanceof StompUser user) {
            this.recipients.put(sessionId, new Recipient(user.authUser(), new ConcurrentHashMap<>()));
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        this.recipients.remove(event.getSessionId());
    }

    @Override
    public @Nullable Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE
                || destination == null || !(destination.startsWith(ChatDestinations.UNIVERSITY_TOPIC_PREFIX)
                || destination.startsWith(ChatDestinations.ALLIANCE_TOPIC_PREFIX))) {
            return message; // 오류 큐·heartbeat·연결/종료 프레임은 권한 조회 실패에도 전달한다.
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        Recipient recipient = sessionId == null ? null : this.recipients.get(sessionId);
        if (recipient == null) {
            return null; // 발행 메시지의 simpUser는 수신자 인증 근거가 아니다.
        }
        try {
            this.authorization.requireAllowed(recipient.user(), destination);
            recipient.notified().remove(destination);
            return message;
        } catch (BusinessException denied) {
            notifyDenied(sessionId, recipient, destination, denied.getErrorCode());
        } catch (RuntimeException unavailable) {
            log.warn("채팅 수신 권한 저장소 확인 실패", unavailable);
            notifyDenied(sessionId, recipient, destination, ErrorCode.COMMON_INTERNAL_ERROR);
        }
        // ChatWebSocketConfig에서 앞에 배치한 완료 콜백이 다음 프레임을 해제한다.
        return null;
    }

    private void notifyDenied(String sessionId, Recipient recipient, String destination, ErrorCode code) {
        if (recipient.notified().put(destination, code) == code) {
            return; // 안내만 중복 억제한다. 권한은 다음 본문마다 DB에서 다시 확인한다.
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        try {
            // Spring의 세션 직접 라우팅: 이름과 simpSessionId가 같으면 해당 세션만 찾는다.
            // 회원 이름으로 보내면 세션이 이미 사라졌을 때 같은 회원의 다른 세션으로 확장될 수 있다.
            this.messaging.convertAndSendToUser(sessionId, "/queue/errors", ChatErrorResponse.of(code),
                    headers.getMessageHeaders());
        } catch (RuntimeException unavailable) {
            recipient.notified().remove(destination, code);
            log.warn("채팅 수신 권한 오류 안내 전달 실패", unavailable);
        }
    }

    private record Recipient(AuthUser user, Map<String, ErrorCode> notified) { }
}
