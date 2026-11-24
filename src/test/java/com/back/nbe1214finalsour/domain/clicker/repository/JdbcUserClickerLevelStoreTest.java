package com.back.nbe1214finalsour.domain.clicker.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
public class JdbcUserClickerLevelStoreTest {
    @Autowired
    private JdbcUserClickerLevelStore userClickerLevelStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        // 2레벨 클리커 (V1에는 1레벨만 있음. 나중에 시드가 들어와도 충돌하지 않게 ON CONFLICT)
        jdbcTemplate.update(
                "INSERT INTO clicker (id, click_power, required_score) VALUES (2, 20, 300) ON CONFLICT (id) DO NOTHING");

        // users는 학교·학과 FK가 필수라 먼저 만든다
        Long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('클리커테스트대') RETURNING id", Long.class);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);

        // clicker_id는 DEFAULT 1 → 가입 직후 1레벨
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '클리커유저', 'clicker@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "a".repeat(64));
    }

    @Test
    @DisplayName("유저의 현재 클리커 레벨을 DB에서 조회한다")
    void findLevel() {
        // given: 기본값(1)이 아닌 레벨로 바꿔 둔다
        jdbcTemplate.update("UPDATE users SET clicker_id = 2 WHERE id = ?", userId);

        // when
        long level = userClickerLevelStore.findLevel(userId);

        // then
        assertThat(level).isEqualTo(2L);
    }
}
