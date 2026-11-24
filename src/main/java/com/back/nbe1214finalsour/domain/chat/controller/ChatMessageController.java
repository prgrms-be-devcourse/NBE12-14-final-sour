package com.back.nbe1214finalsour.domain.chat.controller;

import com.back.nbe1214finalsour.domain.chat.dto.ChatErrorResponse;
import com.back.nbe1214finalsour.domain.chat.dto.ChatSendRequest;
import com.back.nbe1214finalsour.domain.chat.service.ChatMessageService;
import com.back.nbe1214finalsour.domain.chat.websocket.StompUser;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * 채팅 보내기 (STOMP {@code /app/chat/university}, {@code /app/chat/alliance}, 계획 §3-1).
 *
 * <p>받는 쪽에는 보낸 사람도 포함된다. 보낸 사람도 방 구독으로 자기 메시지를 받는다(서버 간 전달, {@code ChatRedisSubscriber}).
 * 처리 중 에러는 연결을 유지하고 보낸 세션의 {@code /user/queue/errors}로만 보낸다 (D15).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    @MessageMapping("/chat/university")
    public void sendToUniversity(@Payload ChatSendRequest request, StompUser user) {
        this.chatMessageService.sendToUniversity(user.authUser(), request.content());
    }

    /** 내 학교가 지금 속한 연합 방으로 보낸다. 방은 요청이 아니라 서버가 정한다 (명세 §3-7) */
    @MessageMapping("/chat/alliance")
    public void sendToAlliance(@Payload ChatSendRequest request, StompUser user) {
        this.chatMessageService.sendToAlliance(user.authUser(), request.content());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorResponse handleException(Exception e) {
        return ChatErrorResponse.of(toErrorCode(e));
    }

    private static ErrorCode toErrorCode(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException.getErrorCode();
        }
        // 본문이 없는 프레임
        if (e instanceof MethodArgumentNotValidException) {
            return ErrorCode.CHAT_INVALID_CONTENT;
        }
        // JSON이 아닌 본문
        if (e instanceof MessageConversionException) {
            log.warn("채팅 메시지 본문을 읽지 못함: {}", e.getMessage());
            return ErrorCode.COMMON_INVALID_INPUT;
        }
        log.error("채팅 메시지 처리 중 예상하지 못한 오류", e);
        return ErrorCode.COMMON_INTERNAL_ERROR;
    }
}
