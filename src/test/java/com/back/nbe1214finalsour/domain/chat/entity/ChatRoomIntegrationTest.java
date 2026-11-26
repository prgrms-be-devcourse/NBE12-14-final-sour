package com.back.nbe1214finalsour.domain.chat.entity;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.repository.ChatRoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class ChatRoomIntegrationTest {
    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loadsMigratedUniversityRoom() {
        Long universityId = jdbcTemplate.queryForObject(
                "SELECT university_id FROM chat_room ORDER BY university_id LIMIT 1", Long.class);
        String universityName = jdbcTemplate.queryForObject(
                "SELECT name FROM university WHERE id = ?", String.class, universityId);
        ChatRoom room = chatRoomRepository.findById(universityId).orElseThrow();
        assertThat(room.getUniversityName()).isEqualTo(universityName);
        assertThat(room.isNew()).isFalse();
    }
}
