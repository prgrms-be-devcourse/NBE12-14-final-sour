package com.back.nbe1214finalsour.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

// @Transactional을 쓰지 않는다: 두 스레드가 각자 트랜잭션을 만들어 커밋해야 동시 UPDATE가 실제로 겹친다.
// 그래서 롤백되지 않으므로 만든 데이터를 @AfterEach에서 직접 지운다
@IntegrationTest
class PaymentConfirmConcurrencyTest {

    private static final String FIRST_KEY = "tgen_20261127160000Conc1";
    private static final String SECOND_KEY = "tgen_20261127160000Conc2"; // uk_payment_payment_key라 스레드마다 다르게

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long universityId;
    private Long departmentId;
    private Long userId;
    private Long paymentId;
    private String orderId;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('결제동시성대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '결제동시성', 'payment-concurrency@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "2".repeat(64));
        Payment payment = paymentRepository.saveAndFlush(Payment.ready(userId, ChargeAmount.KRW_5000));
        paymentId = payment.getId();
        orderId = payment.getOrderId();
    }

    @AfterEach
    void tearDown() {
        // 자식 → 부모 순서로 지운다. 정리가 실패하면 다른 테스트가 깨지므로 삭제도 단언한다
        assertThat(jdbcTemplate.update("DELETE FROM payment WHERE id = ?", paymentId)).isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)).isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM department WHERE id = ?", departmentId)).isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문에 승인 시작이 동시에 두 번 오면 하나만 1행을 받는다")
    void onlyOneOfConcurrentStartsWins() throws Exception {
        // given
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();

        // when
        try {
            String[] paymentKeys = {FIRST_KEY, SECOND_KEY};
            for (String paymentKey : paymentKeys) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(); // 두 스레드가 모두 준비될 때까지 기다렸다가 동시에 출발한다
                    // 람다 안에서 새 트랜잭션을 열고 UPDATE를 실행한다. 반환값(바뀐 행 수)이 이 스레드의 결과다
                    return transactionTemplate.execute(
                            status -> paymentRepository.startIfReady(orderId, userId, 5_000L, paymentKey));
                }));
            }
            ready.await();
            go.countDown();

            int totalUpdated = 0;
            for (Future<Integer> future : futures) {
                totalUpdated += future.get();
            }

            // then
            assertThat(totalUpdated).isEqualTo(1);
            Payment found = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        } finally {
            executor.shutdownNow();
        }
    }
}
