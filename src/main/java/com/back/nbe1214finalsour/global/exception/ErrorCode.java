package com.back.nbe1214finalsour.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 모든 API 오류 코드.
 *
 * <p>규칙 (API 명세서 §1 공통 에러 코드)
 * <ul>
 *   <li>상수 이름과 {@code code} 문자열을 같게 쓴다.</li>
 *   <li>코드는 {@code 도메인접두어_의미} 형식이다. 예: {@code USER_NOT_FOUND}</li>
 *   <li>자기 도메인 구역의 끝에만 추가한다. 여러 명이 이 파일을 함께 고치므로 머지 충돌을 줄이기 위함이다.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== COMMON =====
    COMMON_INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_INVALID_INPUT", "입력값이 올바르지 않습니다."),
    COMMON_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_UNAUTHORIZED", "로그인이 필요합니다."),
    COMMON_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "COMMON_INVALID_TOKEN", "유효하지 않은 토큰입니다."),
    COMMON_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "COMMON_EXPIRED_TOKEN", "토큰이 만료되었습니다."),
    COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_FORBIDDEN", "권한이 없습니다."),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_NOT_FOUND", "요청한 경로를 찾을 수 없습니다."),
    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON_METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
    COMMON_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON_UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 Content-Type입니다."),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_INTERNAL_ERROR", "서버 오류가 발생했습니다."),

    COMMON_REDIS_MEMBER_NOT_NUMERIC(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_REDIS_MEMBER_NOT_NUMERIC", "서버 오류가 발생했습니다."),

    // ===== AUTH (1번) =====
    AUTH_EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "AUTH_EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다."),
    AUTH_UNSUPPORTED_DOMAIN(HttpStatus.BAD_REQUEST, "AUTH_UNSUPPORTED_DOMAIN", "등록되지 않은 학교 이메일 도메인입니다."),
    AUTH_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_COOLDOWN", "인증 코드 요청이 너무 잦습니다. 잠시 후 다시 시도해주세요."),
    AUTH_INVALID_CODE(HttpStatus.BAD_REQUEST, "AUTH_INVALID_CODE", "인증 코드가 일치하지 않습니다."),
    AUTH_CODE_EXPIRED(HttpStatus.GONE, "AUTH_CODE_EXPIRED", "인증 코드가 만료되었습니다. 코드를 다시 요청해주세요."),
    AUTH_CODE_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_CODE_ATTEMPTS_EXCEEDED", "인증 코드를 5번 틀렸습니다. 코드를 다시 요청해주세요."),
    AUTH_INVALID_SIGNUP_TOKEN(HttpStatus.BAD_REQUEST, "AUTH_INVALID_SIGNUP_TOKEN", "가입 토큰이 만료되었거나 올바르지 않습니다. 이메일 인증을 다시 해주세요."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "로그인이 만료되었습니다. 다시 로그인해주세요."),
    AUTH_LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_LOGIN_LOCKED", "로그인을 5번 실패해 10분 동안 로그인할 수 없습니다."),
    /** 다른 기기에서 로그인해 이 기기의 세션이 끝남 (계정당 1기기, SCRUM-83). 재발급하지 말고 로그인 화면으로 */
    AUTH_SESSION_REPLACED(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_REPLACED", "다른 기기에서 로그인해 로그아웃되었습니다. 다시 로그인해주세요."),

    // ===== USER (1번) =====
    USER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "USER_NICKNAME_DUPLICATED", "이미 사용 중인 닉네임입니다."),
    USER_DEPARTMENT_MISMATCH(HttpStatus.BAD_REQUEST, "USER_DEPARTMENT_MISMATCH", "소속 학교의 학과가 아닙니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "회원을 찾을 수 없습니다."),
    USER_SUSPENDED(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "이용이 제한된 계정입니다."),
    USER_INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "USER_INVALID_PASSWORD", "비밀번호가 올바르지 않습니다."),

    // ===== CLICK (2번) =====
    CLICK_DUPLICATE_SUBMISSION(HttpStatus.CONFLICT, "CLICK_DUPLICATE_SUBMISSION", "이미 처리한 제출입니다."),
    CLICK_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "CLICK_TOO_FREQUENT", "제출 요청이 너무 많습니다."),
    CLICK_COUNT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "CLICK_COUNT_EXCEEDED", "클릭 수가 너무 많습니다."),

    // ===== UNIV / RANK (3번) =====
    UNIV_NOT_FOUND(HttpStatus.NOT_FOUND, "UNIV_NOT_FOUND", "학교를 찾을 수 없습니다."),

    // ===== FILE (3번 업로드 모듈, 프로필 수정이 먼저 사용) =====
    FILE_INVALID_URL(HttpStatus.BAD_REQUEST, "FILE_INVALID_URL", "허용되지 않은 파일 주소입니다."),

    // ===== ALLY / MATCH / GUEST (3번) =====
    ALLY_APPLICATION_PENDING(HttpStatus.CONFLICT, "ALLY_APPLICATION_PENDING", "이미 처리 대기 중인 연합 가입 신청이 있습니다."),
    ALLY_UNIVERSITY_ALREADY_JOINED(HttpStatus.CONFLICT, "ALLY_UNIVERSITY_ALREADY_JOINED", "이미 연합에 소속된 학교입니다."),
    MATCH_ALREADY_REQUESTED(HttpStatus.CONFLICT, "MATCH_ALREADY_REQUESTED", "이미 대결을 신청했습니다."),
    GUEST_ALREADY_LIKED(HttpStatus.CONFLICT, "GUEST_ALREADY_LIKED", "이미 좋아요를 눌렀습니다."),

    // ===== CLICKER / ITEM / PAY (4번) =====
    PAY_ALREADY_PROCESSED(HttpStatus.CONFLICT, "PAY_ALREADY_PROCESSED", "이미 처리된 결제입니다."),
    CLICKER_MAX_LEVEL(HttpStatus.BAD_REQUEST, "CLICKER_MAX_LEVEL", "이미 최고 레벨입니다."),
    CLICKER_NOT_ENOUGH_SCORE(HttpStatus.BAD_REQUEST, "CLICKER_NOT_ENOUGH_SCORE", "업그레이드에 필요한 기여도가 부족합니다."),
    CLICKER_UPGRADE_CONFLICT(HttpStatus.CONFLICT, "CLICKER_UPGRADE_CONFLICT", "다른 업그레이드 요청이 먼저 처리되었습니다. 다시 시도해주세요."),
    ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "아이템을 찾을 수 없습니다."),
    ITEM_NOT_ENOUGH_SCORE(HttpStatus.BAD_REQUEST, "ITEM_NOT_ENOUGH_SCORE", "아이템 구매에 필요한 기여도가 부족합니다.")
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;

    /**
     * 프레임워크가 정한 HTTP 상태(404, 405 등)를 공통 오류 코드로 바꾼다.
     * 전용 코드가 없는 4xx는 {@link #COMMON_INVALID_INPUT}, 5xx는 {@link #COMMON_INTERNAL_ERROR}다.
     * 응답의 HTTP 상태는 바꾸지 않고 원래 상태를 그대로 쓴다.
     */
    public static ErrorCode commonFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> COMMON_UNAUTHORIZED;
            case 403 -> COMMON_FORBIDDEN;
            case 404 -> COMMON_NOT_FOUND;
            case 405 -> COMMON_METHOD_NOT_ALLOWED;
            case 415 -> COMMON_UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? COMMON_INVALID_INPUT : COMMON_INTERNAL_ERROR;
        };
    }
}
