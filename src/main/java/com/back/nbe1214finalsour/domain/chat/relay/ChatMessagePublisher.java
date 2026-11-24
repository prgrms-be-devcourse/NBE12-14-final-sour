package com.back.nbe1214finalsour.domain.chat.relay;

import com.back.nbe1214finalsour.domain.chat.dto.ChatMessageResponse;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 채팅 메시지를 Redis 채널 {@code chat:univ:{학교 ID}}로 발행한다 (D4). 모든 서버의 {@link ChatRedisSubscriber}가 받아
 * 자기 서버에 연결된 구독자에게 보낸다.
 *
 * <p>Redis Pub/Sub은 메시지를 보관하지 않는다(at-most-once). 그 순간 연결이 끊긴 서버의 구독자는 이 메시지를 실시간으로 받지
 * 못하고, 이전 메시지 조회(D8)로 본다.
 */
@Component
public class ChatMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public ChatMessagePublisher(StringRedisTemplate redisTemplate, JsonMapper jsonMapper) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** 실패하면 Redis 예외를 그대로 던진다. 처리 방식(D22)은 보내기 서비스가 정한다 */
    public void publishToUniversity(long universityId, ChatMessageResponse message) {
        this.redisTemplate.convertAndSend(RedisKey.CHAT_UNIV_CHANNEL.of(universityId),
                this.jsonMapper.writeValueAsString(message));
    }

    public void publishToAlliance(long allianceId, ChatMessageResponse message) {
        this.redisTemplate.convertAndSend(RedisKey.CHAT_ALLIANCE_CHANNEL.of(allianceId),
                this.jsonMapper.writeValueAsString(message));
    }
}
