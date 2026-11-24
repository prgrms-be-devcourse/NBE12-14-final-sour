package com.back.nbe1214finalsour.domain.chat.websocket;

/**
 * 채팅 STOMP 목적지 (계획 §3-1, 명세서 §3-7). 구독 권한 검사와 서버 간 전달이 같은 문자열을 쓰도록 한곳에 둔다.
 */
public final class ChatDestinations {

    /** 학교 방 구독 경로의 앞부분. 뒤에 학교 ID가 붙는다 */
    public static final String UNIVERSITY_TOPIC_PREFIX = "/topic/chat/universities/";
    /** 연합 방 구독 경로의 앞부분. 뒤에 연합 ID가 붙는다 */
    public static final String ALLIANCE_TOPIC_PREFIX = "/topic/chat/alliances/";
    /** 나에게만 오는 에러 */
    public static final String ERROR_QUEUE = "/user/queue/errors";
    /** 클라이언트가 보내는(발행) 경로의 앞부분 */
    public static final String APP_PREFIX = "/app/";

    private ChatDestinations() {
    }

    public static String university(long universityId) {
        return UNIVERSITY_TOPIC_PREFIX + universityId;
    }

    public static String alliance(long allianceId) {
        return ALLIANCE_TOPIC_PREFIX + allianceId;
    }
}
