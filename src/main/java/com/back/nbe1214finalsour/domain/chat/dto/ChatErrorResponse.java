package com.back.nbe1214finalsour.domain.chat.dto;

import com.back.nbe1214finalsour.global.exception.ErrorCode;

/** 채팅 에러 본문 {@code {"code","message"}}. {@code /user/queue/errors}와 STOMP ERROR 프레임이 같은 모양을 쓴다 (계획 D15, D25) */
public record ChatErrorResponse(String code, String message) {

    public static ChatErrorResponse of(ErrorCode errorCode) {
        return new ChatErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }
}
