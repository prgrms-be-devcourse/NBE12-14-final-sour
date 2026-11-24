package com.back.nbe1214finalsour.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.ulid.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-17T05:00:00.123Z");
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private ChatMessage create(Instant createdAt) {
        return ChatMessage.create(ChatRoomId.university(12), 1024L, "대대대짱", "서울대학교", "안녕", createdAt, SEVEN_DAYS);
    }

    @Test
    @DisplayName("학교 방 ID는 UNIV#{학교 ID}다")
    void universityRoomId() {
        assertThat(ChatRoomId.university(12)).isEqualTo("UNIV#12");
    }

    @Test
    @DisplayName("TTL은 작성 시각 + 보관 기간의 epoch 초다 (DynamoDB TTL 형식)")
    void ttl_isCreatedAtPlusRetentionInEpochSeconds() {
        ChatMessage message = create(CREATED_AT);

        assertThat(message.getTtl()).isEqualTo(CREATED_AT.plus(SEVEN_DAYS).getEpochSecond());
    }

    @Test
    @DisplayName("메시지 ID는 작성 시각(밀리초)을 담은 ULID다")
    void messageId_isUlidOfCreatedAt() {
        ChatMessage message = create(CREATED_AT);

        assertThat(Ulid.isValid(message.getMessageId())).isTrue();
        assertThat(Ulid.getInstant(message.getMessageId())).isEqualTo(CREATED_AT.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("같은 밀리초에 만든 메시지도 만든 순서대로 ID가 커진다")
    void messageIds_increaseWithinSameMillisecond() {
        String first = create(CREATED_AT).getMessageId();
        String second = create(CREATED_AT).getMessageId();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("TTL 시각이 되면 만료다 (TTL 직전은 만료가 아니다)")
    void isExpired_fromTtlSecond() {
        ChatMessage message = create(CREATED_AT);
        Instant ttl = Instant.ofEpochSecond(message.getTtl());

        assertThat(message.isExpired(ttl.minusSeconds(1))).isFalse();
        assertThat(message.isExpired(ttl)).isTrue();
    }
}
