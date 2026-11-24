package com.back.nbe1214finalsour.domain.credit.repository;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.credit.entity.CreditHistory;
import com.back.nbe1214finalsour.domain.credit.entity.CreditHistoryType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class CreditHistoryRepositoryTest {

    private static final UUID KEY = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Autowired
    private CreditHistoryRepository creditHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long otherUserId;
    private Long userItemId;
    private Long logoItemId;

    @BeforeEach
    void setUp() {
        Long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('원장테스트대') RETURNING id", Long.class);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = insertUser(universityId, departmentId, "원장유저", "ledger@test.com", "f");
        otherUserId = insertUser(universityId, departmentId, "원장다른유저", "ledger-other@test.com", "0");

        // 로고 아이템은 종류별로 하나만 허용(uk_item_logo_type)하므로, 이미 있으면 그걸 쓴다
        jdbcTemplate.update("""
                INSERT INTO item (name, content, type, pay_type, price, duration, multiplier)
                VALUES ('원장 테스트 학교 로고', '우리 학교 로고 변경', 'LOGO_UNIVERSITY', 'CREDIT', 3900, NULL, NULL)
                ON CONFLICT DO NOTHING
                """);
        logoItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM item WHERE type = 'LOGO_UNIVERSITY'", Long.class);

        userItemId = insertLogoUserItem(userId);
    }

    // 이 회원의 로고 구매 이력 (영구라 expired_at 없음)
    private Long insertLogoUserItem(Long ownerId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO user_item (user_id, item_id, price, logo_image_url)
                VALUES (?, ?, 3900, 'https://cdn.devcproject.cloud/custom-logo/1/abc.png')
                RETURNING id
                """, Long.class, ownerId, logoItemId);
    }

    private Long insertUser(Long universityId, Long departmentId, String nickname, String email, String hashChar) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, ?, ?, ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, nickname, email, hashChar.repeat(64));
    }

    @Test
    @DisplayName("크레딧 사용 내역(USE)을 저장하면 입력한 값 그대로 DB에서 다시 읽힌다")
    void saveUseHistory() {
        // when
        CreditHistory saved = creditHistoryRepository.saveAndFlush(
                CreditHistory.use(userId, userItemId, 3900L, 1100L, null));
        entityManager.clear();   // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        CreditHistory found = creditHistoryRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getUserItemId()).isEqualTo(userItemId);
        assertThat(found.getPaymentId()).isNull();
        assertThat(found.getType()).isEqualTo(CreditHistoryType.USE);
        assertThat(found.getAmount()).isEqualTo(3900L);
        assertThat(found.getBalanceAfter()).isEqualTo(1100L);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 회원의 구매 이력에는 원장을 연결할 수 없다 (복합 FK)")
    void cannotLinkOtherUsersUserItem() {
        assertThatThrownBy(() -> creditHistoryRepository.saveAndFlush(
                CreditHistory.use(otherUserId, userItemId, 3900L, 1100L, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 회원이 같은 멱등 키로 사용 내역을 두 번 저장하면 DB가 거부한다")
    void rejectsSameIdempotencyKeyForSameUser() {
        // given
        Long secondUserItemId = insertLogoUserItem(userId);
        creditHistoryRepository.saveAndFlush(CreditHistory.use(userId, userItemId, 3900L, 1100L, KEY));

        // when & then
        assertThatThrownBy(() -> creditHistoryRepository.saveAndFlush(
                CreditHistory.use(userId, secondUserItemId, 3900L, 1100L, KEY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_credit_history_user_idempotency_key");
    }

    @Test
    @DisplayName("다른 회원이 같은 멱등 키를 쓰면 둘 다 저장된다")
    void allowsSameIdempotencyKeyForDifferentUsers() {
        // given
        Long otherUserItemId = insertLogoUserItem(otherUserId);

        // when
        creditHistoryRepository.saveAndFlush(CreditHistory.use(userId, userItemId, 3900L, 1100L, KEY));
        creditHistoryRepository.saveAndFlush(CreditHistory.use(otherUserId, otherUserItemId, 3900L, 1100L, KEY));

        // then
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credit_history WHERE idempotency_key = ?", Long.class, KEY))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("멱등 키가 없는 사용 내역은 같은 회원이라도 여러 건 저장된다")
    void allowsMultipleNullIdempotencyKeys() {
        // given
        Long secondUserItemId = insertLogoUserItem(userId);

        // when
        creditHistoryRepository.saveAndFlush(CreditHistory.use(userId, userItemId, 3900L, 1100L, null));
        creditHistoryRepository.saveAndFlush(CreditHistory.use(userId, secondUserItemId, 3900L, 1100L, null));

        // then
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM credit_history WHERE user_id = ? AND idempotency_key IS NULL",
                Long.class, userId))
                .isEqualTo(2L);
    }
}