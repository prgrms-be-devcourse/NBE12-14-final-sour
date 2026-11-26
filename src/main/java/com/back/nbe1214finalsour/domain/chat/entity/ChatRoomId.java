package com.back.nbe1214finalsour.domain.chat.entity;

/**
 * 응답과 Redis 중계에 쓰는 방 식별 문자열이다. 저장하지 않고 방 FK에서 만든다.
 *
 * <p>학교 방은 {@code UNIV#{학교 ID}}, 연합 방은 {@code ALLIANCE#{연합 ID}}다 (채팅 2단계, SCRUM-182).
 */
public final class ChatRoomId {

    private static final String UNIVERSITY_PREFIX = "UNIV#";
    private static final String ALLIANCE_PREFIX = "ALLIANCE#";

    private ChatRoomId() {
    }

    public static String university(long universityId) {
        return UNIVERSITY_PREFIX + universityId;
    }

    public static String alliance(long allianceId) {
        return ALLIANCE_PREFIX + allianceId;
    }
}
