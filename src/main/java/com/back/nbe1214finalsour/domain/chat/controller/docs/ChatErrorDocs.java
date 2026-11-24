package com.back.nbe1214finalsour.domain.chat.controller.docs;

/** 채팅 이력 API에서 공통으로 발생하는 오류 설명. */
public final class ChatErrorDocs {
    public static final String INVALID_INPUT = "`COMMON_INVALID_INPUT` 커서, 크기 또는 경로 값 오류";
    public static final String UNAUTHORIZED = "`COMMON_UNAUTHORIZED` 인증 필요 / `COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / "
            + "`COMMON_EXPIRED_TOKEN` 만료된 토큰 / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함";
    public static final String NOT_MEMBER = "`CHAT_NOT_MEMBER` 해당 채팅방 소속이 아님";

    private ChatErrorDocs() {
    }
}
