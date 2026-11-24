package com.back.nbe1214finalsour.domain.chat.entity;

/**
 * 채팅방 ID. DynamoDB {@code chat_message} 테이블의 파티션 키({@code room_id}) 값이다.
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
