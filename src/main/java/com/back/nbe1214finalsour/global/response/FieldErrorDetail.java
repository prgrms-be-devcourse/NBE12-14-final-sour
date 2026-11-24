package com.back.nbe1214finalsour.global.response;

/**
 * 입력 검증 실패 시 {@link ApiResponse#data()}에 담는 필드별 오류.
 */
public record FieldErrorDetail(String field, String reason) {
}
