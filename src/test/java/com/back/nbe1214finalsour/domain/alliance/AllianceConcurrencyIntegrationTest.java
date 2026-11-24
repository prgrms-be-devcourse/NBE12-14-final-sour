package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AllianceConcurrencyIntegrationTest {

    @Autowired
    private AllianceService allianceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("동일 학교가 동시에 연합 생성을 요청하면 하나만 성공한다")
    void createAlliance_concurrentRequestsAllowOnlyOne()
            throws Exception {

        // given 1. 테스트용 학교 생성
        Long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id",
                Long.class,
                "동시성테스트대_" + UUID.randomUUID()
        );

        Long departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO department (university_id, name)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                universityId,
                "컴퓨터공학과"
        );

        String email = "concurrent-"
                + UUID.randomUUID() + "@test.ac.kr";

        User user = User.create(
                universityId,
                departmentId,
                "동시성테스트회원",
                email,
                emailHasher.hash(email),
                passwordEncoder.encode("Passw0rd!"),
                OffsetDateTime.now()
        );

        Long userId = userRepository.saveAndFlush(user).getId();

        String nameA = "동시연합A_"
                + UUID.randomUUID().toString().substring(0, 8);

        String nameB = "동시연합B_"
                + UUID.randomUUID().toString().substring(0, 8);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 두 요청이 준비되었는지 확인
        CountDownLatch ready = new CountDownLatch(2);

        // 두 요청을 거의 동시에 출발시키는 신호
        CountDownLatch start = new CountDownLatch(1);

        try {
            // when 1. 첫 번째 연합 생성 요청
            Future<Boolean> resultA = executor.submit(
                    () -> tryCreate(userId, nameA, ready, start)
            );

            // when 2. 두 번째 연합 생성 요청
            Future<Boolean> resultB = executor.submit(
                    () -> tryCreate(userId, nameB, ready, start)
            );

            // 두 스레드가 준비될 때까지 대기
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 동시에 시작!
            start.countDown();

            boolean successA = resultA.get(30, TimeUnit.SECONDS);
            boolean successB = resultB.get(30, TimeUnit.SECONDS);

            // then 1. 정확히 하나만 성공해야 한다.
            assertThat(successA ^ successB).isTrue();

            // then 2. DB에 연합이 하나만 남아 있어야 한다.
            Long allianceCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM alliance
                    WHERE name IN (?, ?)
                    """,
                    Long.class,
                    nameA,
                    nameB
            );

            assertThat(allianceCount).isEqualTo(1L);

            // then 3. 해당 학교의 ACCEPTED 가입도 1건
            Long memberCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM alliance_university
                    WHERE university_id = ?
                      AND status = 'ACCEPTED'
                      AND deleted_at IS NULL
                    """,
                    Long.class,
                    universityId
            );

            assertThat(memberCount).isEqualTo(1L);

        } finally {
            // 테스트용 스레드 종료
            start.countDown();
            executor.shutdownNow();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "동시성 테스트 스레드가 종료되지 않았습니다."
                );
            }

            // 실제 DB에 저장된 테스트 데이터 정리
            jdbcTemplate.update(
                    """
                    DELETE FROM alliance_university
                    WHERE alliance_id IN (
                        SELECT id FROM alliance
                        WHERE name IN (?, ?)
                    )
                    """,
                    nameA,
                    nameB
            );

            jdbcTemplate.update(
                    "DELETE FROM alliance WHERE name IN (?, ?)",
                    nameA,
                    nameB
            );

            jdbcTemplate.update(
                    "DELETE FROM users WHERE id = ?",
                    userId
            );

            jdbcTemplate.update(
                    "DELETE FROM department WHERE id = ?",
                    departmentId
            );

            jdbcTemplate.update(
                    "DELETE FROM university WHERE id = ?",
                    universityId
            );
        }
    }

    // 두 스레드에서 실행할 연합 생성 작업
    private boolean tryCreate(
            Long userId,
            String name,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {

        // 준비 완료 알림
        ready.countDown();

        // 시작 신호 기다리기
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "동시성 테스트 시작 신호를 받지 못했습니다."
            );
        }

        try {
            allianceService.createAlliance(
                    userId,
                    name,
                    24
            );

            return true;

        } catch (RuntimeException e) {
            System.out.println(
                    "연합 생성 실패 [" + name + "]: "
                            + e.getClass().getSimpleName()
                            + " - " + e.getMessage()
            );

            return false;
        }
    }
}
