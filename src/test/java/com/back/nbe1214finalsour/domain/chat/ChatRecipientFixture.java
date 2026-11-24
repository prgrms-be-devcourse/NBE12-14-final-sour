package com.back.nbe1214finalsour.domain.chat;

import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 구독 등록뿐 아니라 학교 본문을 받는 테스트에 필요한 실제 회원과 학교. */
public record ChatRecipientFixture(long userId, long universityId, long departmentId) {

    public static ChatRecipientFixture create(JdbcTemplate jdbc, UserRepository users, EmailHasher hasher) {
        String suffix = UUID.randomUUID().toString();
        long universityId = jdbc.queryForObject("INSERT INTO university (name) VALUES (?) RETURNING id", Long.class,
                "채팅대" + suffix.substring(0, 8));
        long departmentId = jdbc.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class, universityId);
        String email = "chat-" + suffix + "@chat-test.ac.kr";
        User user = users.saveAndFlush(User.create(universityId, departmentId, "채팅" + suffix.substring(0, 8),
                email, hasher.hash(email), "{noop}unused", OffsetDateTime.now()));
        return new ChatRecipientFixture(user.getId(), universityId, departmentId);
    }

    public void remove(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM users WHERE id = ?", this.userId);
        jdbc.update("DELETE FROM department WHERE id = ?", this.departmentId);
        jdbc.update("DELETE FROM university WHERE id = ?", this.universityId);
    }
}
