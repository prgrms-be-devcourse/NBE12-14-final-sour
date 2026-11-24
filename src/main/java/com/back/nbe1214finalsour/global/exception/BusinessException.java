package com.back.nbe1214finalsour.global.exception;

import lombok.Getter;

/**
 * 도메인 규칙 위반을 알리는 예외. 응답의 HTTP 상태, 코드, 메시지는 {@link ErrorCode}에서 정해진다.
 *
 * <p>{@code detail}은 로그에만 남기는 상세 정보다. 응답 본문에는 들어가지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }
}
