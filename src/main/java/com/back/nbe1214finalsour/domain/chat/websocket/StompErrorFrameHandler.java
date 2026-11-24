package com.back.nbe1214finalsour.domain.chat.websocket;

import com.back.nbe1214finalsour.domain.chat.dto.ChatErrorResponse;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompConversionException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프레임 처리 중 난 예외(인증·구독 권한 실패, 너무 큰 프레임 등)를 STOMP ERROR 프레임으로 바꾼다.
 * Spring은 ERROR 프레임을 보낸 뒤 연결을 끊는다.
 *
 * <p>ERROR 프레임의 {@code message} 헤더와 본문 {@code {"code","message"}}에 오류 코드를 담는다. 클라이언트는 코드로 다음 행동을 고른다.
 * 예: {@code COMMON_EXPIRED_TOKEN}이면 토큰을 재발급하고 다시 연결한다. {@code AUTH_SESSION_REPLACED}면 로그인 화면으로 간다.
 *
 * <p>메시지 처리({@code @MessageMapping}) 중 난 에러는 연결을 끊지 않고 {@code /user/queue/errors}로 보낸다(계획 D15, {@code ChatMessageController}).
 */
@Slf4j
@Component
public class StompErrorFrameHandler extends StompSubProtocolErrorHandler {

    private final JsonMapper jsonMapper;

    public StompErrorFrameHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public @Nullable Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage,
                                                                        Throwable ex) {
        ErrorCode errorCode = isFrameBeforeConnect(clientMessage) ? ErrorCode.COMMON_UNAUTHORIZED : toErrorCode(ex);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(errorCode.getCode());
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        byte[] body = this.jsonMapper.writeValueAsBytes(ChatErrorResponse.of(errorCode));
        MessageHeaders headers = accessor.getMessageHeaders();
        return MessageBuilder.createMessage(body, headers);
    }

    /**
     * CONNECT 없이 보낸 첫 프레임인지. Spring은 이런 프레임을 인터셉터에 넘기기 전에 "Unknown session"으로 거부하고,
     * 그때는 프레임에 세션 ID를 붙이기 전이다 ({@code StompSubProtocolHandler#handleMessageFromClient}).
     * 클라이언트 잘못이므로 서버 오류가 아니라 인증 필요로 답한다.
     */
    private static boolean isFrameBeforeConnect(@Nullable Message<byte[]> clientMessage) {
        if (clientMessage == null) {
            return false;
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(clientMessage);
        boolean connect = accessor.getCommand() == StompCommand.CONNECT || accessor.getCommand() == StompCommand.STOMP;
        if (!connect && accessor.getSessionId() == null) {
            log.warn("STOMP 프레임 거부 COMMON_UNAUTHORIZED: CONNECT 전에 보낸 {}", accessor.getCommand());
            return true;
        }
        return false;
    }

    private static ErrorCode toErrorCode(Throwable ex) {
        BusinessException business = findCause(ex, BusinessException.class);
        if (business != null) {
            log.warn("STOMP 프레임 거부 {}: {}", business.getErrorCode().getCode(), business.getMessage());
            return business.getErrorCode();
        }
        StompConversionException conversion = findCause(ex, StompConversionException.class);
        if (conversion != null) {
            // 형식이 틀렸거나 chat.websocket.message-size-limit보다 큰 프레임
            log.warn("STOMP 프레임 해석 실패: {}", conversion.getMessage());
            return ErrorCode.COMMON_INVALID_INPUT;
        }
        log.error("STOMP 프레임 처리 중 예상하지 못한 오류", ex);
        return ErrorCode.COMMON_INTERNAL_ERROR;
    }

    private static <T extends Throwable> @Nullable T findCause(Throwable ex, Class<T> type) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
