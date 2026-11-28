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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class PaymentRepositoryTest {

    private static final String PAYMENT_KEY = "tgen_20261127160000AbCd1";

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

    @Test
    @DisplayName("본인의 READY 주문이면 ABORTED로 바꾸고 실패 코드·메시지를 남긴다")
    void abortIfReadyAbortsOwnReadyOrder() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));

        // when
        int updated = paymentRepository.abortIfReady(
                payment.getOrderId(), userId, "PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다.");
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(found.getFailCode()).isEqualTo("PAY_PROCESS_CANCELED");
        assertThat(found.getFailMessage()).isEqualTo("사용자에 의해 결제가 취소되었습니다.");
    }

    @Test
    @DisplayName("남의 READY 주문이면 바꾸지 않고 0을 돌려준다")
    void abortIfReadyIgnoresOthersOrder() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));
        Long otherUserId = insertOtherUser();

        // when
        int updated = paymentRepository.abortIfReady(
                payment.getOrderId(), otherUserId, "PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다.");
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getFailCode()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = PaymentStatus.class,
            names = {"IN_PROGRESS", "DONE", "ABORTED", "EXPIRED", "CANCELED"})
    @DisplayName("READY가 아닌 주문이면 바꾸지 않고 0을 돌려준다")
    void abortIfReadyIgnoresNonReadyOrder(PaymentStatus status) {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));
        forceStatus(payment.getId(), status);
        entityManager.clear(); // JDBC로 바꾼 상태를 모르는 READY 엔티티를 컨텍스트에 남기지 않음

        // when
        int updated = paymentRepository.abortIfReady(
                payment.getOrderId(), userId, "PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다.");
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(found.getStatus()).isEqualTo(status);
        assertThat(found.getFailCode()).isNull();
    }

    @Test
    @DisplayName("메시지 없이 실패를 기록하면 코드만 저장하고 메시지는 NULL로 둔다")
    void abortIfReadySavesNullMessage() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));

        // when
        int updated = paymentRepository.abortIfReady(payment.getOrderId(), userId, "PAY_PROCESS_CANCELED", null);
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getFailMessage()).isNull();
        assertThat(found.getFailCode()).isEqualTo("PAY_PROCESS_CANCELED");
    }

    @Test
    @DisplayName("본인의 READY 주문이고 금액이 같으면 IN_PROGRESS로 바꾸고 결제 키를 저장한다")
    void startIfReadyStartsOwnReadyOrder() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));

        // when
        int updated = paymentRepository.startIfReady(payment.getOrderId(), userId, 5_000L, PAYMENT_KEY);
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(found.getPaymentKey()).isEqualTo(PAYMENT_KEY);
    }

    @Test
    @DisplayName("남의 READY 주문이면 바꾸지 않고 0을 돌려준다")
    void startIfReadyIgnoresOthersOrder() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));
        Long otherUserId = insertOtherUser();

        // when
        int updated = paymentRepository.startIfReady(payment.getOrderId(), otherUserId, 5_000L, PAYMENT_KEY);
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getPaymentKey()).isNull();
    }

    @Test
    @DisplayName("금액이 주문 금액과 다르면 바꾸지 않고 0을 돌려준다")
    void startIfReadyIgnoresAmountMismatch() {
        // given
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));

        // when
        int updated = paymentRepository.startIfReady(payment.getOrderId(), userId, 10_000L, PAYMENT_KEY);
        entityManager.clear(); // 영속성 컨텍스트를 비워 DB에서 다시 읽음

        // then
        Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updated).isZero();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(found.getPaymentKey()).isNull();
    }

    // 다른 회원: 첫 회원과 같은 대학·학과로 만든다. 닉네임·이메일 해시는 유니크라 다른 테스트가 쓰지 않는 값을 쓴다
    private Long insertOtherUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                SELECT university_id, department_id, '결제타인', 'payment-other@test.com', ?, 'password', now()
                  FROM users WHERE id = ?
                RETURNING id
                """, Long.class, "1".repeat(64), userId);
    }

    // READY가 아닌 상태로 바꾼다. DB 제약이 요구하는 컬럼을 같이 채운다
    // DONE: ck_payment_done (payment_key, approved_at) / CANCELED: 여기에 ck_payment_canceled (canceled_at)까지
    private void forceStatus(Long paymentId, PaymentStatus status) {
        String paymentKey = "test_payment_key_" + paymentId; // uk_payment_payment_key라 주문마다 다르게
        if (status == PaymentStatus.DONE) {
            jdbcTemplate.update(
                    "UPDATE payment SET status = 'DONE', payment_key = ?, approved_at = now() WHERE id = ?",
                    paymentKey, paymentId);
            return;
        }
        if (status == PaymentStatus.CANCELED) {
            jdbcTemplate.update(
                    "UPDATE payment SET status = 'CANCELED', payment_key = ?, approved_at = now(), canceled_at = now() WHERE id = ?",
                    paymentKey, paymentId);
            return;
        }
        // IN_PROGRESS, ABORTED, EXPIRED는 상태 외에 필요한 컬럼이 없다
        jdbcTemplate.update("UPDATE payment SET status = ? WHERE id = ?", status.name(), paymentId);
    }
}
