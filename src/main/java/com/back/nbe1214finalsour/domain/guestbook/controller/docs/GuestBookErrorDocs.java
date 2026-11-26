package com.back.nbe1214finalsour.domain.guestbook.controller.docs;

/** 방명록 API 에러 설명. */
final class GuestBookErrorDocs {

    static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` ID 또는 페이지 요청값이 올바르지 않음";

    static final String CREATE_BAD_REQUEST = "`COMMON_INVALID_INPUT` ID 또는 본문이 올바르지 않음 / `GUEST_OWN_UNIVERSITY` 자기 학교에 작성할 수 없음";

    static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 토큰 없음 / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN` / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";

    static final String SUSPENDED = "`USER_SUSPENDED` 이용이 제한된 계정";

    static final String UNIVERSITY_NOT_FOUND = "`UNIV_NOT_FOUND` 학교를 찾을 수 없음";

    static final String USER_NOT_FOUND = "`USER_NOT_FOUND` 회원을 찾을 수 없음";

    static final String GUEST_NOT_FOUND = "`GUEST_NOT_FOUND` 방명록을 찾을 수 없음";

    static final String LIKE_NOT_FOUND = "`GUEST_LIKE_NOT_FOUND` 좋아요를 누른 기록이 없음";

    static final String ALREADY_LIKED = "`GUEST_ALREADY_LIKED` 이미 좋아요를 누름";

    static final String TOO_FREQUENT = "`GUEST_TOO_FREQUENT` 회원당 1분에 1개만 작성 가능";

    private GuestBookErrorDocs() {
    }
}
