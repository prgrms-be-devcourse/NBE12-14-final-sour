package com.back.nbe1214finalsour.global.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link BaseTimeEntity}의 시각을 DB 한 곳(열 기본값 {@code now()}와 {@code set_updated_at()} 트리거)이 정하고,
 * 엔티티에는 그 값이 그대로 채워지는지 PostgreSQL로 확인한다 (GitHub 이슈 #19, Jira SCRUM-70).
 *
 * <p>{@code now()}는 트랜잭션 시작 시각이라, 기대값으로 각 트랜잭션의 {@code select now()}를 쓴다. 앱 시계가 값을 넣으면
 * 이 값과 달라져 실패한다. 트랜잭션마다 시각을 비교해야 하므로 테스트 트랜잭션 롤백 대신 커밋하고 {@link #deleteProbe()}로 지운다.
 *
 * <p>테스트 전용 테이블을 만들지 않으려고 {@code created_at}·{@code updated_at}이 있는 {@code clicker} 테이블을 빌려 쓴다.
 */
@IntegrationTest
class BaseTimeEntityTest {

    private static final long PROBE_ID = 9_001L;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void deleteProbe() {
        jdbcTemplate.update("DELETE FROM clicker WHERE id = ?", PROBE_ID);
    }

    @Test
    @DisplayName("저장하면 created_at·updated_at은 DB가 트랜잭션 시작 시각으로 채우고, 엔티티에도 그 값이 들어온다")
    void insertUsesDatabaseTime() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant transactionStart = databaseNow();
            pause();

            TimeProbe probe = new TimeProbe(PROBE_ID, 1);
            entityManager.persist(probe);
            entityManager.flush();

            assertThat(probe.getCreatedAt().toInstant()).isEqualTo(transactionStart);
            assertThat(probe.getUpdatedAt().toInstant()).isEqualTo(transactionStart);
            assertThat(storedCreatedAt()).isEqualTo(transactionStart);
            assertThat(storedUpdatedAt()).isEqualTo(transactionStart);
        });
    }

    @Test
    @DisplayName("다른 트랜잭션에서 수정하면 updated_at만 그 트랜잭션 시각으로 바뀌고, 엔티티 값이 DB 값과 같다")
    void updateUsesDatabaseTime() {
        Instant createdAt = transactionTemplate.execute(status -> {
            TimeProbe probe = new TimeProbe(PROBE_ID, 1);
            entityManager.persist(probe);
            entityManager.flush();
            return probe.getCreatedAt().toInstant();
        });
        pause();

        transactionTemplate.executeWithoutResult(status -> {
            Instant transactionStart = databaseNow();
            pause();

            TimeProbe probe = entityManager.find(TimeProbe.class, PROBE_ID);
            probe.changeClickPower(2);
            entityManager.flush();

            assertThat(probe.getUpdatedAt().toInstant()).isEqualTo(transactionStart).isAfter(createdAt);
            assertThat(probe.getCreatedAt().toInstant()).isEqualTo(createdAt);
            assertThat(storedUpdatedAt()).isEqualTo(transactionStart);
            assertThat(storedCreatedAt()).isEqualTo(createdAt);
        });
    }

    @Test
    @DisplayName("한 트랜잭션에서 만들고 고쳐도 updated_at이 created_at보다 이르지 않고, 엔티티 값이 DB 값과 같다 (이슈 #19 재현)")
    void insertThenUpdateInOneTransactionStaysConsistent() {
        transactionTemplate.executeWithoutResult(status -> {
            TimeProbe probe = new TimeProbe(PROBE_ID, 1);
            entityManager.persist(probe);
            entityManager.flush();
            pause();

            probe.changeClickPower(2);
            entityManager.flush();

            assertThat(probe.getUpdatedAt().toInstant()).isEqualTo(storedUpdatedAt());
            assertThat(probe.getCreatedAt().toInstant()).isEqualTo(storedCreatedAt());
            assertThat(storedUpdatedAt()).isAfterOrEqualTo(storedCreatedAt());
        });
    }

    @Test
    @DisplayName("JDBC로 직접 고친 행도 트리거가 updated_at을 그 트랜잭션 시각으로 바꾼다")
    void jdbcUpdateGoesThroughTrigger() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.persist(new TimeProbe(PROBE_ID, 1));
            entityManager.flush();
        });
        pause();

        transactionTemplate.executeWithoutResult(status -> {
            Instant transactionStart = databaseNow();

            jdbcTemplate.update("UPDATE clicker SET click_power = 3 WHERE id = ?", PROBE_ID);

            assertThat(storedUpdatedAt()).isEqualTo(transactionStart).isAfter(storedCreatedAt());
        });
    }

    private Instant databaseNow() {
        return jdbcTemplate.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    private Instant storedCreatedAt() {
        return jdbcTemplate.queryForObject("SELECT created_at FROM clicker WHERE id = ?", OffsetDateTime.class, PROBE_ID)
                .toInstant();
    }

    private Instant storedUpdatedAt() {
        return jdbcTemplate.queryForObject("SELECT updated_at FROM clicker WHERE id = ?", OffsetDateTime.class, PROBE_ID)
                .toInstant();
    }

    /** 앱 시계가 값을 넣었다면 트랜잭션 시작 시각과 확실히 달라지도록 DB 정밀도(마이크로초)보다 길게 쉰다 */
    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Entity(name = "BaseTimeEntityTestProbe")
    @Table(name = "clicker")
    static class TimeProbe extends BaseTimeEntity {

        @Id
        private Long id;

        private int clickPower;

        protected TimeProbe() {
        }

        TimeProbe(Long id, int clickPower) {
            this.id = id;
            this.clickPower = clickPower;
        }

        void changeClickPower(int clickPower) {
            this.clickPower = clickPower;
        }
    }
}
