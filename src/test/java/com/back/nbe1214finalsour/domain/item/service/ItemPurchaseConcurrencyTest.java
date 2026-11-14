package com.back.nbe1214finalsour.domain.item.service;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 여러 스레드가 각자 트랜잭션을 커밋해야 하므로 @Transactional(테스트 후 롤백)을 쓰지 않고 직접 정리한다
@IntegrationTest
class ItemPurchaseConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final long POTION_2X_ID = 9201L;   // 기여도 300, 2배
    private static final long POTION_4X_ID = 9202L;   // 기여도 300, 4배

    @Autowired
    private ItemPurchaseService itemPurchaseService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private Long universityId;
    private Long userId;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('물약동시성대') RETURNING id", Long.class);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '물약동시성유저', 'potion-concurrency@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "d".repeat(64));

        jdbcTemplate.update("""
                INSERT INTO item (id, name, content, type, pay_type, price, duration, multiplier)
                VALUES (?, '동시성 2배 물약', '1시간 동안 클릭 점수 2배', 'MULTIPLIER', 'CONTRIBUTION', 300, 3600, 2.0),
                       (?, '동시성 4배 물약', '1시간 동안 클릭 점수 4배', 'MULTIPLIER', 'CONTRIBUTION', 300, 3600, 4.0)
                """, POTION_2X_ID, POTION_4X_ID);
        itemService.loadItemTable();   // 서버 시작 때 캐시된 아이템 목록을 다시 읽음
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(effectKey());
        redisTemplate.delete(rankKey());
        redisTemplate.opsForSet().remove("dirty:user", userId.toString());

        // FK 순서대로 삭제: user_item → item·users → department → university
        jdbcTemplate.update("DELETE FROM user_item WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM item WHERE id IN (?, ?)", POTION_2X_ID, POTION_4X_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        itemService.loadItemTable();   // 아이템 목록 캐시를 원래대로
    }

    private String rankKey() {
        return "rank:univ:" + universityId + ":user";
    }

    private String effectKey() {
        return "user:" + userId + ":effect";
    }

    private void giveContribution(long score) {
        redisTemplate.opsForZSet().add(rankKey(), userId.toString(), score);
    }

    @Test
    @DisplayName("같은 회원이 잔액보다 많이 동시에 구매해도 잔액만큼만 성공하고, 기여도는 음수가 되지 않는다 (이중 지출 없음)")
    void concurrentPurchase_noOverspend() throws InterruptedException {
        // given: 기여도 1000, 물약 300 → 최대 3번 (1000 = 300 × 3 + 100)
        giveContribution(1000);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notEnoughCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        // when: 10개 스레드가 동시에 같은 물약 구매
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        itemPurchaseService.purchase(userId, universityId, POTION_2X_ID);
                        successCount.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.ITEM_NOT_ENOUGH_SCORE) {
                            notEnoughCount.incrementAndGet();   // 정상적인 실패
                        } else {
                            unexpectedErrors.add(e);
                        }
                    } catch (Throwable e) {
                        unexpectedErrors.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // then
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(3);
        assertThat(notEnoughCount.get()).isEqualTo(7);
        // 남은 기여도 100 (3번 × 300만 차감)
        assertThat(redisTemplate.opsForZSet().score(rankKey(), userId.toString())).isEqualTo(100.0);
        // 구매 이력도 성공한 만큼만
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_item WHERE user_id = ?", Long.class, userId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("같은 회원이 서로 다른 물약을 동시에 사도 효과는 하나의 물약 값으로만 남는다 (필드가 섞이지 않음)")
    void concurrentPurchase_effectNotMixed() throws InterruptedException {
        // given: 모두 성공할 만큼 충분한 기여도
        giveContribution(100_000);

        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREAD_COUNT);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        // when: 2배 물약과 4배 물약을 번갈아 동시에 구매
        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                long itemId = (i % 2 == 0) ? POTION_2X_ID : POTION_4X_ID;
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        itemPurchaseService.purchase(userId, universityId, itemId);
                    } catch (Throwable e) {
                        unexpectedErrors.add(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        }

        // then: 마지막으로 덮어쓴 물약 하나의 값만 온전히 남음
        assertThat(unexpectedErrors).isEmpty();
        Map<Object, Object> effect = redisTemplate.opsForHash().entries(effectKey());
        assertThat(effect).hasSize(3);
        if (String.valueOf(POTION_2X_ID).equals(effect.get("itemId"))) {
            assertThat(effect.get("multiplier")).isEqualTo("2.0");
        } else {
            assertThat(effect.get("itemId")).isEqualTo(String.valueOf(POTION_4X_ID));
            assertThat(effect.get("multiplier")).isEqualTo("4.0");
        }
        // 덮어써도 만료는 걸려 있음 (TTL 없는 영구 효과가 생기지 않음)
        assertThat(redisTemplate.getExpire(effectKey())).isPositive();
    }
}