package com.back.nbe1214finalsour.domain.chat.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.back.nbe1214finalsour.domain.chat.dto.ChatMessageResponse;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.chat.entity.ChatRoomId;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

class ChatRedisSubscriberTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    // 앱과 같은 시간대(spring.jackson.time-zone). 읽은 createdAt을 이 시간대 오프셋으로 맞춘다
    private final JsonMapper jsonMapper = JsonMapper.builder().defaultTimeZone(TimeZone.getTimeZone(ClockConfig.KST)).build();
    private final ChatRedisSubscriber subscriber = new ChatRedisSubscriber(this.messagingTemplate, this.jsonMapper);

    private static final byte[] PATTERN = ChatRedisSubscriber.UNIVERSITY_CHANNEL_PATTERN.getBytes(StandardCharsets.UTF_8);

    private ChatMessageResponse message(long universityId) {
        return new ChatMessageResponse("01JTEST", ChatRoomId.university(universityId), 7L, "닉네임", "테스트대학교", "안녕",
                OffsetDateTime.of(2026, 11, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)));
    }

    private void receive(String channel, String body) {
        this.subscriber.onMessage(new DefaultMessage(channel.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), PATTERN);
    }

    @Test
    @DisplayName("패턴은 학교 채널 이름(RedisKey.CHAT_UNIV_CHANNEL)의 모든 학교다")
    void patternCoversUniversityChannels() {
        assertThat(ChatRedisSubscriber.UNIVERSITY_CHANNEL_PATTERN).isEqualTo("chat:univ:*");
    }

    @Test
    @DisplayName("학교 채널의 메시지를 그 학교 방 목적지로 보낸다")
    void forwardsToUniversityTopic() {
        ChatMessageResponse message = message(42L);

        receive("chat:univ:42", this.jsonMapper.writeValueAsString(message));

        verify(this.messagingTemplate).convertAndSend("/topic/chat/universities/42", message);
    }

    // 패턴 chat:univ:*는 이 이름들도 받는다
    @ParameterizedTest
    @ValueSource(strings = {"chat:univ:", "chat:univ:abc", "chat:univ:42:x", "chat:univ:-1", "chat:univ:1234567890123456789"})
    @DisplayName("학교 ID 형식이 아닌 채널은 버린다")
    void dropsInvalidChannel(String channel) {
        receive(channel, this.jsonMapper.writeValueAsString(message(42L)));

        verify(this.messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("연합 채널의 메시지를 그 연합 방 목적지로 보낸다. 패턴은 연합 채널 이름(RedisKey.CHAT_ALLIANCE_CHANNEL)의 모든 연합이다")
    void forwardsToAllianceTopic() {
        assertThat(ChatRedisSubscriber.ALLIANCE_CHANNEL_PATTERN).isEqualTo("chat:alliance:*");
        ChatMessageResponse message = new ChatMessageResponse("01JTEST", ChatRoomId.alliance(5L), 7L, "닉네임", "테스트대학교",
                "연합 안녕", OffsetDateTime.of(2026, 11, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)));

        receive("chat:alliance:5", this.jsonMapper.writeValueAsString(message));

        verify(this.messagingTemplate).convertAndSend("/topic/chat/alliances/5", message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat:alliance:", "chat:alliance:abc", "chat:alliance:5:x", "chat:alliances:5"})
    @DisplayName("연합 ID 형식이 아닌 채널은 버린다")
    void dropsInvalidAllianceChannel(String channel) {
        receive(channel, this.jsonMapper.writeValueAsString(
                new ChatMessageResponse("01JTEST", ChatRoomId.alliance(5L), 7L, "닉네임", "테스트대학교", "안녕", null)));

        verify(this.messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("학교 방 메시지가 연합 채널로, 연합 방 메시지가 학교 채널로 와도 버린다 (같은 숫자 ID)")
    void dropsRoomKindMismatch() {
        receive("chat:alliance:42", this.jsonMapper.writeValueAsString(message(42L)));
        receive("chat:univ:42", this.jsonMapper.writeValueAsString(
                new ChatMessageResponse("01JTEST", ChatRoomId.alliance(42L), 7L, "닉네임", "테스트대학교", "안녕", null)));

        verify(this.messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("본문을 읽지 못하면 버린다")
    void dropsUnreadableBody() {
        receive("chat:univ:42", "{깨진 JSON");

        verify(this.messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("채널의 학교와 본문의 방이 다르면 버린다")
    void dropsRoomMismatch() {
        receive("chat:univ:42", this.jsonMapper.writeValueAsString(message(43L)));

        verify(this.messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("응답의 createdAt은 저장 시각을 한국 시간 오프셋으로 바꾼 값이다")
    void responseCreatedAtIsKst() {
        ChatMessage saved = ChatMessage.create(ChatRoomId.university(42L), 7L, "닉네임", "테스트대학교", "안녕",
                Instant.parse("2026-11-17T09:30:00Z"), Duration.ofDays(7));

        assertThat(ChatMessageResponse.from(saved).createdAt())
                .isEqualTo(OffsetDateTime.of(2026, 11, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)));
    }
}
