package com.back.nbe1214finalsour.domain.alliance.controller.docs;

/** 연합 API 에러 응답 설명. */
final class AllianceErrorDocs {

    static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";

    static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` 입력값이 올바르지 않음";

    static final String NOT_FOUND = "`ALLY_NOT_FOUND` 연합 없음";

    static final String USER_NOT_FOUND = "`USER_NOT_FOUND` 회원 없음";

    static final String ACTIVE_USER = "`ALLY_USER_NOT_ACTIVE` 활성 회원이 아님";

    static final String NOT_MEMBER = "`ALLY_NOT_MEMBER` 연합 소속 학교 회원이 아님";

    static final String ALREADY_JOINED = "`ALLY_UNIVERSITY_ALREADY_JOINED` 이미 연합에 소속된 학교";

    static final String JOIN_CONFLICT = "`ALLY_NOT_ACTIVE` 비활성 연합 / `ALLY_UNIVERSITY_ALREADY_JOINED` 이미 가입 / `ALLY_APPLICATION_PENDING` 대기 중 신청 존재 / `ALLY_FULL` 정원 초과";

    static final String APPLICATION_MISSING = "`USER_NOT_FOUND` 회원 없음 / `ALLY_NOT_FOUND` 연합 없음 / `ALLY_APPLICATION_NOT_FOUND` 신청 없음";

    static final String APPLICATION_CONFLICT = "`ALLY_NOT_ACTIVE` 비활성 연합 / `ALLY_APPLICATION_NOT_PENDING` 이미 처리된 신청";

    static final String APPROVE_CONFLICT = "`ALLY_NOT_ACTIVE` 비활성 연합 / `ALLY_APPLICATION_NOT_PENDING` 이미 처리된 신청 / `ALLY_FULL` 정원 초과 / `ALLY_UNIVERSITY_ALREADY_JOINED` 이미 가입";

    static final String MATCH_NOT_FOUND = "`MATCH_NOT_FOUND` 대결 없음";

    static final String MATCH_MISSING = "`USER_NOT_FOUND` 회원 없음 / `ALLY_NOT_FOUND` 연합 없음 / `MATCH_NOT_FOUND` 대결 없음";

    static final String MATCH_BAD_REQUEST = "`COMMON_INVALID_INPUT` 잘못된 입력 / `MATCH_INVALID_SCHEDULE` 잘못된 예약 시각";

    static final String MATCH_REQUEST_CONFLICT = "`ALLY_NOT_ACTIVE` 비활성 연합 / `MATCH_MEMBERS_INCOMPLETE` 학교 수 부족 / `MATCH_ALREADY_REQUESTED` 이미 신청 / `MATCH_SCHEDULE_CONFLICT` 기존 예약 대결 존재 / `MATCH_INSUFFICIENT_TIME` 남은 시간 부족";

    static final String MATCH_ACCEPT_CONFLICT = "`MATCH_NOT_PENDING` 대기 중이 아님 / `MATCH_REQUEST_EXPIRED` 신청 기한 만료 / `ALLY_NOT_ACTIVE` 비활성 연합 / `MATCH_MEMBERS_INCOMPLETE` 학교 수 부족 / `MATCH_SCHEDULE_CONFLICT` 기존 예약 대결 존재 / `MATCH_INSUFFICIENT_TIME` 남은 시간 부족";

    static final String MATCH_CANCEL_CONFLICT = "`MATCH_NOT_PENDING` 대기 중이 아님";

    static final String MEMBER_OR_ACTIVE = "`ALLY_USER_NOT_ACTIVE` 활성 회원이 아님 / `ALLY_NOT_MEMBER` 연합 소속 학교 회원이 아님";

    private AllianceErrorDocs() {
    }
}
