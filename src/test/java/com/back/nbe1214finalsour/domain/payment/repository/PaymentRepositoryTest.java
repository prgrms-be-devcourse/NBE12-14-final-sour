package com.back.nbe1214finalsour.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long userId;

    @BeforeEach
    void setUp() {
        Long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('결제테스트대') RETURNING id", Long.class);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class,
                universityId);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '결제유저', 'payment@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "a".repeat(64));
    }

    @Test
    @DisplayName("ready로 만든 주문을 저장하면 값이 그대로 읽히고 생성·수정 시각은 DB가 채운다")
    void savesReadyOrderWithDatabaseGeneratedTimes() {
        // given
        Payment payment = Payment.ready(userId, ChargeAmount.KRW_5000);

        // when
        Payment saved = paymentRepository.saveAndFlush(payment);
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
        assertThat(found.getOrderName()).isEqualTo("크레딧 5,000");
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getAmount()).isEqualTo(5_000L);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
