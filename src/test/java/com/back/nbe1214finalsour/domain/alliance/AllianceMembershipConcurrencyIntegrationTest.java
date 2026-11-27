package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceInvitationService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AllianceMembershipConcurrencyIntegrationTest {

    @Autowired
    private AllianceService allianceService;

    @Autowired
    private AllianceInvitationService invitations;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> allianceIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> departmentIds = new ArrayList<>();
    private final List<Long> universityIds = new ArrayList<>();

    @Test
    @DisplayName("두 가입 신청을 동시에 승인해도 한 학교만 승인된다")
    void approveApplication_concurrentOnlyOneSucceeds() throws Exception {

        // given 1. 서로 다른 학교의 테스트 회원 3명 생성
        Long founderUserId = createTestUser();
        Long firstUserId = createTestUser();
        Long secondUserId = createTestUser();

        // given 2. 최초 학교가 연합 생성
        Alliance alliance = allianceService.createAlliance(
                founderUserId,
                "동시승인연합_" + UUID.randomUUID().toString().substring(0, 8),
                24
        );

        Long allianceId = alliance.getId();
        allianceIds.add(allianceId);

        // given 3. 서로 다른 두 학교가 가입 신청
        AllianceUniversity firstApplication =
                allianceService.applyToAlliance(
                        firstUserId,
                        allianceId
                );

        AllianceUniversity secondApplication =
                allianceService.applyToAlliance(
                        secondUserId,
                        allianceId
                );

        // 두 요청을 동시에 실행하기 위한 준비
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            // when 1. 첫 번째 신청 승인 요청
            Future<Boolean> firstResult = executor.submit(
                    () -> tryApprove(
                            founderUserId,
                            firstApplication.getId(),
                            ready,
                            start
                    )
            );

            // when 2. 두 번째 신청 승인 요청
            Future<Boolean> secondResult = executor.submit(
                    () -> tryApprove(
                            founderUserId,
                            secondApplication.getId(),
                            ready,
                            start
                    )
            );

            // 두 스레드가 준비될 때까지 대기
            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 동시에 승인 시작
            start.countDown();

            boolean firstSuccess =
                    firstResult.get(30, TimeUnit.SECONDS);

            boolean secondSuccess =
                    secondResult.get(30, TimeUnit.SECONDS);

            // then 1. 두 요청 중 정확히 하나만 성공
            assertThat(firstSuccess ^ secondSuccess)
                    .isTrue();

            // then 2. 최초 학교 포함 총 2개 학교만 가입
            assertThat(countStatus(allianceId, "ACCEPTED"))
                    .isEqualTo(2L);

            // then 3. 나머지 신청은 자동 거절
            assertThat(countStatus(allianceId, "REJECTED"))
                    .isEqualTo(1L);

            // then 4. 대기 신청은 남지 않아야 한다
            assertThat(countStatus(allianceId, "PENDING"))
                    .isEqualTo(0L);

            // then 5. 실제 두 신청 상태 확인
            String firstStatus =
                    findStatus(firstApplication.getId());

            String secondStatus =
                    findStatus(secondApplication.getId());

            assertThat(List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(
                            "ACCEPTED",
                            "REJECTED"
                    );

        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("가입 신청과 초대를 동시에 승인해도 정원은 초과되지 않는다")
    void invitationAndApplicationConcurrentApproval() throws Exception {
        Long founder = createTestUser();
        Long invited = createTestUser();
        Long applicant = createTestUser();
        Long invitedSchool = userRepository.findById(invited).orElseThrow().getUniversityId();
        Alliance alliance = allianceService.createAlliance(founder, "동시초대_" + UUID.randomUUID().toString().substring(0, 8), 24);
        allianceIds.add(alliance.getId());
        AllianceUniversity invitation = invitations.invite(founder, alliance.getId(), invitedSchool);
        AllianceUniversity application = allianceService.applyToAlliance(applicant, alliance.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> tryApprove(founder, application.getId(), ready, start));
            Future<Boolean> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 신호 시간 초과");
                try { invitations.approve(invited, invitation.getId()); return true; }
                catch (BusinessException e) { return false; }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS) ^ second.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(countStatus(alliance.getId(), "ACCEPTED")).isEqualTo(2L);
            assertThat(countStatus(alliance.getId(), "REJECTED")).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("한 학교가 서로 다른 연합의 초대를 동시에 수락해도 하나에만 가입한다")
    void sameSchoolConcurrentInvitations() throws Exception {
        Long firstFounder = createTestUser();
        Long secondFounder = createTestUser();
        Long target = createTestUser();
        Long school = userRepository.findById(target).orElseThrow().getUniversityId();
        Alliance firstAlliance = allianceService.createAlliance(firstFounder, "초대경쟁1_" + UUID.randomUUID().toString().substring(0, 8), 24);
        Alliance secondAlliance = allianceService.createAlliance(secondFounder, "초대경쟁2_" + UUID.randomUUID().toString().substring(0, 8), 24);
        allianceIds.add(firstAlliance.getId());
        allianceIds.add(secondAlliance.getId());
        AllianceUniversity firstInvite = invitations.invite(firstFounder, firstAlliance.getId(), school);
        AllianceUniversity secondInvite = invitations.invite(secondFounder, secondAlliance.getId(), school);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> tryAcceptInvitation(target, firstInvite.getId(), ready, start));
            Future<Boolean> second = executor.submit(() -> tryAcceptInvitation(target, secondInvite.getId(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS) ^ second.get(30, TimeUnit.SECONDS)).isTrue();
            Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM alliance_university WHERE university_id = ? AND status = 'ACCEPTED' AND deleted_at IS NULL", Long.class, school);
            assertThat(count).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean tryAcceptInvitation(Long user, Long invitation, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("시작 신호 시간 초과");
        try { invitations.approve(user, invitation); return true; }
        catch (BusinessException | org.springframework.dao.DataIntegrityViolationException e) { return false; }
    }

    // ===== 동시 승인 요청 =====

    private boolean tryApprove(
            Long userId,
            Long applicationId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "동시성 테스트 시작 신호를 받지 못했습니다."
            );
        }

        try {
            allianceService.approveApplication(
                    userId,
                    applicationId
            );

            return true;

        } catch (BusinessException e) {
            // 이미 정원이 차거나 신청이 거절된 경우
            return false;
        }
    }

    // ===== 테스트 회원 생성 =====

    private Long createTestUser() {
        String suffix =
                UUID.randomUUID().toString().substring(0, 8);

        Long universityId = jdbcTemplate.queryForObject(
                """
                INSERT INTO university (name)
                VALUES (?)
                RETURNING id
                """,
                Long.class,
                "동시성학교_" + suffix
        );

        universityIds.add(universityId);

        Long departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO department (university_id, name)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                universityId,
                "테스트학과"
        );

        departmentIds.add(departmentId);

        String email =
                "alliance-" + UUID.randomUUID() + "@test.ac.kr";

        User user = User.create(
                universityId,
                departmentId,
                "테스트회원_" + suffix,
                email,
                emailHasher.hash(email),
                passwordEncoder.encode("Passw0rd!"),
                OffsetDateTime.now()
        );

        Long userId =
                userRepository.saveAndFlush(user).getId();

        userIds.add(userId);

        return userId;
    }

    // ===== PostgreSQL 조회 =====

    private Long countStatus(Long allianceId, String status) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM alliance_university
                WHERE alliance_id = ?
                  AND status = ?
                  AND deleted_at IS NULL
                """,
                Long.class,
                allianceId,
                status
        );
    }

    private String findStatus(Long applicationId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM alliance_university
                WHERE id = ?
                """,
                String.class,
                applicationId
        );
    }

    // ===== 테스트 데이터 정리 =====

    @AfterEach
    void cleanUp() {

        // 가입 신청 및 가입 이력 삭제
        for (Long allianceId : allianceIds) {
            jdbcTemplate.update(
                    "DELETE FROM alliance_university WHERE alliance_id = ?",
                    allianceId
            );
        }

        // 연합 삭제
        for (Long allianceId : allianceIds) {
            jdbcTemplate.update("DELETE FROM alliance_chat_room WHERE alliance_id = ?", allianceId);
            jdbcTemplate.update(
                    "DELETE FROM alliance WHERE id = ?",
                    allianceId
            );
        }

        // 회원 삭제
        for (Long userId : userIds) {
            jdbcTemplate.update(
                    "DELETE FROM users WHERE id = ?",
                    userId
            );
        }

        // 학과 삭제
        for (Long departmentId : departmentIds) {
            jdbcTemplate.update(
                    "DELETE FROM department WHERE id = ?",
                    departmentId
            );
        }

        // 대학교 삭제
        for (Long universityId : universityIds) {
            jdbcTemplate.update(
                    "DELETE FROM university WHERE id = ?",
                    universityId
            );
        }
    }
}