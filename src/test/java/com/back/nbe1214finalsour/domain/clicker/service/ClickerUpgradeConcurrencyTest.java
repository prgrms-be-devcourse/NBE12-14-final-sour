package com.back.nbe1214finalsour.domain.clicker.service;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.exception.BusinessException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 여러 스레드가 각자 트랜잭션을 커밋해야 하므로 @Transactional(테스트 후 롤백)을 쓰지 않는다
@IntegrationTest
class ClickerUpgradeConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final long INITIAL_SCORE = 10_000L;

    @Autowired
    private ClickerUpgradeService clickerUpgradeService;

    @Autowired
    private ClickerService clickerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String rankKey;

    private Long universityId;
    private Long userId;
    private List<Long> insertedLevels;

    @BeforeEach
    void setUp() {
        // 2·3레벨 추가. 시드로 이미 있으면 건너뛰고, 이 테스트가 실제로 넣은 레벨만 기록해 두었다가 지운다
        insertedLevels = jdbcTemplate.queryForList("""
                INSERT INTO clicker (id, click_power, required_score)
                VALUES (2, 20, 300), (3, 40, 1000)
                ON CONFLICT (id) DO NOTHING
                RETURNING id
                """, Long.class);
        clickerService.loadLevelTable();   // 서버 시작 때 캐시된 레벨표를 다시 읽음

        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('동시성테스트대') RETURNING id", Long.class);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
            VALUES (?, ?, '동시성유저', 'concurrency@test.com', ?, 'password', now())
            RETURNING id
            """, Long.class, universityId, departmentId, "c".repeat(64));

        // 기여도 원본은 교내 개인 순위 ZSet
        rankKey = "rank:univ:" + universityId + ":user";
        redisTemplate.opsForZSet().add(rankKey, userId.toString(), INITIAL_SCORE);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(rankKey);
        redisTemplate.opsForSet().remove("dirty:user", userId.toString());
        redisTemplate.opsForSet().remove("dirty:clicker", userId.toString());
        // 동시성 테스트는 트렌젝션을 걸지 않으므로 직접 데이터 정리해야함
        // FK 순서대로 삭제: users → department → university → clicker
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
        insertedLevels.forEach(level -> jdbcTemplate.update("DELETE FROM clicker WHERE id = ?", level));
        clickerService.loadLevelTable();   // 레벨표 캐시를 원래대로
        redisTemplate.delete("user:" + userId + ":clicker");
    }

    @Test
    @DisplayName("같은 유저가 동시에 업그레이드를 요청해도 성공 횟수만큼만 레벨이 오르고 그 비용만 차감됨")
    void concurrentUpgrade() throws InterruptedException {
        CountDownLatch startSignal = new CountDownLatch(1);              // 모든 스레드를 동시에 출발시키는 신호
        CountDownLatch finished = new CountDownLatch(THREAD_COUNT);      // 모든 스레드가 끝났는지
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT)) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        startSignal.await();
                        clickerUpgradeService.upgrade(userId, universityId);
                        successCount.incrementAndGet();
                    } catch (BusinessException e) {
                        // 충돌, 잔액 부족, 최고 레벨은 정상적인 실패
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

        // 레벨 원본은 Redis. 요청 안에서 DB는 바뀌지 않고 1분 배치가 반영한다
        long finalLevel = Long.parseLong(redisTemplate.opsForValue().get("user:" + userId + ":clicker"));

        long paidScore = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(required_score), 0) FROM clicker WHERE id BETWEEN 2 AND ?",
                Long.class, finalLevel);

        long finalScore = redisTemplate.opsForZSet().score(rankKey, userId.toString()).longValue();

        assertThat(unexpectedErrors).isEmpty();
        assertThat(finalLevel).isGreaterThanOrEqualTo(2L);                  // 최소 한 번은 성공
        assertThat(successCount.get()).isEqualTo((int) (finalLevel - 1));    // 성공 횟수 == 오른 레벨 수
        assertThat(finalScore).isEqualTo(INITIAL_SCORE - paidScore);         // 낸 비용 == 오른 레벨들의 비용
        assertThat(redisTemplate.opsForSet().isMember("dirty:clicker", userId.toString())).isTrue();   // 배치가 DB에 반영할 대상으로 표시됨
        assertThat(jdbcTemplate.queryForObject(
                "SELECT clicker_id FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);      // 요청 안에서 DB 레벨은 그대로
    }
}