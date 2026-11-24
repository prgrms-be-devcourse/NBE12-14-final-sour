package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.repository.ItemRepository;
import com.back.nbe1214finalsour.domain.userItem.entity.UserItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional을 붙이지 않는다: 붙이면 record가 테스트 트랜잭션에 합류해 커밋되지 않는다
@IntegrationTest
class PotionPurchaseRecorderTest {

    private static final long ITEM_ID = 9102L;

    @Autowired
    private PotionPurchaseRecorder recorder;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long universityId;
    private Long departmentId;
    private Long userId;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('기록테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '기록유저', 'recorder@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "c".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO item (id, name, content, type, pay_type, price, duration, multiplier)
                VALUES (?, '기록 테스트 물약', '1시간 동안 클릭 점수 2배', 'MULTIPLIER', 'CONTRIBUTION', 500, 3600, 2.0)
                """, ITEM_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM user_item WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM item WHERE id = ?", ITEM_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM department WHERE id = ?", departmentId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("구매 이력을 기록하면 돌아오기 전에 커밋되어 다른 커넥션에서 조회된다")
    void record_commitsBeforeReturn() {
        // given
        Item potion = itemRepository.findById(ITEM_ID).orElseThrow();
        UserItem userItem = UserItem.ofPotion(userId, potion, OffsetDateTime.now().plusSeconds(potion.getDuration()));

        // when
        UserItem saved = recorder.record(userItem);

        // then
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_item WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
    }
}
