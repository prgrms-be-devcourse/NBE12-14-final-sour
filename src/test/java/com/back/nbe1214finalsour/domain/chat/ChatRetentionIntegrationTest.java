package com.back.nbe1214finalsour.domain.chat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import com.back.nbe1214finalsour.domain.chat.service.ChatRetentionService;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ChatRetentionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired EmailHasher hasher;
    @Autowired ChatMessageRepository messages;
    @Autowired PlatformTransactionManager manager;
    ChatRecipientFixture member;
    ChatRetentionService retention;
    long expired;
    long active;
    static final OffsetDateTime NOW = OffsetDateTime.parse("2000-01-10T00:00:00Z");

    @BeforeEach
    void setup() {
        member = ChatRecipientFixture.create(jdbc, users, hasher);
        jdbc.update("INSERT INTO chat_room (university_id, university_name) VALUES (?, '학교')", member.universityId());
        expired = alliance("EXPIRED");
        active = alliance("ACTIVE");
        retention = new ChatRetentionService(messages, Clock.fixed(Instant.from(NOW), ZoneOffset.UTC), manager, 2, 20);
    }

    long alliance(String status) {
        long id = jdbc.queryForObject("INSERT INTO alliance (name, duration_hours, created_at, expired_at, status) VALUES ('연합', 24, ?, ?, ?) RETURNING id", Long.class, NOW.minusDays(2), NOW.minusDays(1), status);
        jdbc.update("INSERT INTO alliance_chat_room (alliance_id, alliance_name) VALUES (?, '연합')", id);
        return id;
    }

    long message(Long room, Long alliance, OffsetDateTime created) {
        return jdbc.queryForObject("INSERT INTO chat_message (chat_room_id, alliance_chat_room_id, user_id, university_name, nickname, content, created_at) VALUES (?, ?, ?, '학교', '닉', '내용', ?) RETURNING id", Long.class, room, alliance, member.userId(), created);
    }

    boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM chat_message WHERE id = ?)", Boolean.class, id));
    }

    @AfterEach
    void cleanup() {
        if (member == null) return;
        jdbc.update("DELETE FROM chat_message WHERE user_id = ?", member.userId());
        jdbc.update("DELETE FROM alliance_chat_room WHERE alliance_id IN (?, ?)", expired, active);
        jdbc.update("DELETE FROM alliance WHERE id IN (?, ?)", expired, active);
        jdbc.update("DELETE FROM chat_room WHERE university_id = ?", member.universityId());
        member.remove(jdbc);
    }

    @Test
    @DisplayName("학교 경계는 미만이며 연합 정리는 상태를 따르고 방과 연합을 남긴다")
    void boundaryAndIsolation() {
        long old = message(member.universityId(), null, NOW.minusDays(7).minusSeconds(1));
        long boundary = message(member.universityId(), null, NOW.minusDays(7));
        long recent = message(member.universityId(), null, NOW);
        long expiredMessage = message(null, expired, NOW.minusDays(10));
        long activeMessage = message(null, active, NOW.minusDays(10));
        assertThat(retention.deleteUniversityMessages()).isEqualTo(1);
        assertThat(exists(old)).isFalse();
        assertThat(exists(boundary) && exists(recent) && exists(expiredMessage) && exists(activeMessage)).isTrue();
        assertThat(retention.deleteAllianceMessages()).isEqualTo(1);
        assertThat(exists(expiredMessage)).isFalse();
        assertThat(exists(activeMessage) && exists(boundary) && exists(recent)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alliance_chat_room WHERE alliance_id IN (?, ?)", Integer.class, expired, active)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alliance WHERE id IN (?, ?)", Integer.class, expired, active)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_room WHERE university_id = ?", Integer.class, member.universityId())).isEqualTo(1);
    }

    @Test
    @DisplayName("작은 배치 여러 번을 각각 커밋해 다섯 건을 삭제한다")
    void multipleBatches() {
        for (int i = 0; i < 5; i++) message(member.universityId(), null, NOW.minusDays(8));
        assertThat(retention.deleteUniversityMessages()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE user_id = ?", Integer.class, member.userId())).isZero();
    }
}
