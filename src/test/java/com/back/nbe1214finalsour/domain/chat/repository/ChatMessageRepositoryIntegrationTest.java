package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.ChatRecipientFixture;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class ChatMessageRepositoryIntegrationTest {
    @Autowired ChatMessageRepository messages;
    @Autowired ChatRoomRepository rooms;
    @Autowired AllianceChatRoomRepository allianceRooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired EmailHasher hasher;
    ChatRecipientFixture member;
    long allianceId;

    @BeforeEach
    void setup() {
        member = ChatRecipientFixture.create(jdbc, users, hasher);
        jdbc.update("INSERT INTO chat_room (university_id, university_name) VALUES (?, ?)", member.universityId(), "학교");
        allianceId = jdbc.queryForObject("INSERT INTO alliance (name, duration_hours, expired_at) VALUES ('연합', 24, now() + interval '1 day') RETURNING id", Long.class);
        jdbc.update("INSERT INTO alliance_chat_room (alliance_id, alliance_name) VALUES (?, '연합')", allianceId);
    }

    @Test
    @DisplayName("학교와 연합 메시지는 방별로 격리되고 커서 이전의 최신순으로 조회된다")
    void cursorsAndRoomIsolation() {
        ChatMessage first = messages.save(ChatMessage.toUniversity(member.universityId(), member.userId(), "닉", "학교", "첫째"));
        ChatMessage second = messages.save(ChatMessage.toUniversity(member.universityId(), member.userId(), "닉", "학교", "둘째"));
        ChatMessage third = messages.save(ChatMessage.toUniversity(member.universityId(), member.userId(), "닉", "학교", "셋째"));
        ChatMessage alliance = messages.save(ChatMessage.toAlliance(allianceId, member.userId(), "닉", "학교", "연합"));
        ChatRecipientFixture other = ChatRecipientFixture.create(jdbc, users, hasher);
        jdbc.update("INSERT INTO chat_room (university_id, university_name) VALUES (?, '다른 학교')", other.universityId());
        ChatMessage otherMessage = messages.save(ChatMessage.toUniversity(other.universityId(), other.userId(), "닉", "다른 학교", "다른 방"));
        assertThat(messages.findByChatRoomIdOrderByIdDesc(other.universityId(), PageRequest.of(0, 3)))
                .extracting(ChatMessage::getId).containsExactly(otherMessage.getId());
        List<ChatMessage> page = messages.findByChatRoomIdOrderByIdDesc(member.universityId(), PageRequest.of(0, 3));
        assertThat(page).extracting(ChatMessage::getId).containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(messages.findByChatRoomIdAndIdLessThanOrderByIdDesc(member.universityId(), second.getId(), PageRequest.of(0, 3)))
                .extracting(ChatMessage::getId).containsExactly(first.getId());
        assertThat(messages.findByAllianceChatRoomIdOrderByIdDesc(allianceId, PageRequest.of(0, 3)))
                .extracting(ChatMessage::getId).containsExactly(alliance.getId());
        assertThat(messages.findByAllianceChatRoomIdAndIdLessThanOrderByIdDesc(allianceId, alliance.getId(), PageRequest.of(0, 3))).isEmpty();
    }

    @Test
    @DisplayName("학교와 연합 방의 마지막 메시지는 더 오래된 시각으로 갱신되지 않는다")
    void lastContentDoesNotMoveBackwards() {
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        assertThat(rooms.updateLastContent(member.universityId(), "최신", now)).isEqualTo(1);
        assertThat(rooms.updateLastContent(member.universityId(), "오래됨", now.minusSeconds(1))).isZero();
        assertThat(allianceRooms.updateLastContent(allianceId, "최신", now)).isEqualTo(1);
        assertThat(allianceRooms.updateLastContent(allianceId, "오래됨", now.minusSeconds(1))).isZero();
        assertThat(jdbc.queryForObject("SELECT last_content FROM chat_room WHERE university_id = ?", String.class, member.universityId())).isEqualTo("최신");
        assertThat(jdbc.queryForObject("SELECT last_content FROM alliance_chat_room WHERE alliance_id = ?", String.class, allianceId)).isEqualTo("최신");
    }
}
