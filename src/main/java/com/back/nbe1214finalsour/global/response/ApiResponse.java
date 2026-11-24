package com.back.nbe1214finalsour.global.response;

import com.back.nbe1214finalsour.global.exception.ErrorCode;

/**
 * 모든 API의 응답 본문 (API 명세서 §1 응답 형식).
 *
 * <p>HTTP 상태 코드는 본문에 넣지 않고 실제 HTTP 상태로만 전달한다.
 * {@code data}가 {@code null}이어도 필드는 항상 직렬화된다.
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String SUCCESS_MESSAGE = "요청이 성공했습니다.";

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, T data) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), data);
    }
}
