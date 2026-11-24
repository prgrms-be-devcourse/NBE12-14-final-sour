package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class AllianceMembershipIntegrationTest {

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

    @Autowired
    private EntityManager entityManager;

    private TestMember founder;
    private TestMember applicant;
    private TestMember waiting;

    @BeforeEach
    void setUp() {
        founder = createTestMember("founder");
        applicant = createTestMember("applicant");
        waiting = createTestMember("waiting");
    }

    @Test
    @DisplayName("가입 신청 시 PENDING 상태가 실제 DB에 저장된다")
    void applyToAlliance_persistsPending() {
        // given
        Alliance alliance = createAlliance();

        // when
        AllianceUniversity application =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        entityManager.flush();

        // then
        assertThat(application.getId()).isNotNull();
        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);

        assertThat(findStatus(application.getId()))
                .isEqualTo("PENDING");

        assertThat(countByStatus(alliance.getId(), "PENDING"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("가입 승인 시 DB 상태가 ACCEPTED로 변경된다")
    void approveApplication_persistsAccepted() {
        // given
        Alliance alliance = createAlliance();

        AllianceUniversity application =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        // when
        allianceService.approveApplication(
                founder.userId(),
                application.getId()
        );

        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(findStatus(application.getId()))
                .isEqualTo("ACCEPTED");

        assertThat(countByStatus(alliance.getId(), "ACCEPTED"))
                .isEqualTo(2L);

        Long savedApproverId = jdbcTemplate.queryForObject(
                """
                SELECT approver_user_id
                FROM alliance_university
                WHERE id = ?
                """,
                Long.class,
                application.getId()
        );

        assertThat(savedApproverId)
                .isEqualTo(founder.userId());
    }

    @Test
    @DisplayName("가입 거절 시 DB 상태가 REJECTED로 변경된다")
    void rejectApplication_persistsRejected() {
        // given
        Alliance alliance = createAlliance();

        AllianceUniversity application =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        // when
        allianceService.rejectApplication(
                founder.userId(),
                application.getId()
        );

        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(findStatus(application.getId()))
                .isEqualTo("REJECTED");

        assertThat(countByStatus(alliance.getId(), "ACCEPTED"))
                .isEqualTo(1L);

        assertThat(countByStatus(alliance.getId(), "PENDING"))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("가입 승인으로 정원이 차면 다른 신청이 DB에서 자동 거절된다")
    void approveApplication_autoRejectsOtherPending() {
        // given
        Alliance alliance = createAlliance();

        AllianceUniversity firstApplication =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        AllianceUniversity secondApplication =
                allianceService.applyToAlliance(
                        waiting.userId(),
                        alliance.getId()
                );

        assertThat(countByStatus(alliance.getId(), "PENDING"))
                .isEqualTo(2L);

        // when
        allianceService.approveApplication(
                founder.userId(),
                firstApplication.getId()
        );

        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(findStatus(firstApplication.getId()))
                .isEqualTo("ACCEPTED");

        assertThat(findStatus(secondApplication.getId()))
                .isEqualTo("REJECTED");

        assertThat(countByStatus(alliance.getId(), "ACCEPTED"))
                .isEqualTo(2L);

        assertThat(countByStatus(alliance.getId(), "PENDING"))
                .isEqualTo(0L);

        assertThat(countByStatus(alliance.getId(), "REJECTED"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("가입 신청이 거절된 학교는 재신청할 수 있다")
    void rejectApplication_allowsReapplication() {
        // given
        Alliance alliance = createAlliance();

        AllianceUniversity firstApplication =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        allianceService.rejectApplication(
                founder.userId(),
                firstApplication.getId()
        );

        // when
        AllianceUniversity secondApplication =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        alliance.getId()
                );

        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(secondApplication.getId())
                .isNotEqualTo(firstApplication.getId());

        assertThat(findStatus(firstApplication.getId()))
                .isEqualTo("REJECTED");

        assertThat(findStatus(secondApplication.getId()))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("이미 연합에 가입한 학교는 다른 연합에 가입 신청할 수 없다")
    void applyToAlliance_rejectsAlreadyJoinedUniversity() {
        // given
        Alliance firstAlliance = createAlliance();

        TestMember otherFounder = createTestMember("otherFounder");

        Alliance secondAlliance =
                allianceService.createAlliance(
                        otherFounder.userId(),
                        "다른테스트연합",
                        24
                );

        AllianceUniversity application =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        firstAlliance.getId()
                );

        allianceService.approveApplication(
                founder.userId(),
                application.getId()
        );

        // when & then
        assertThatThrownBy(() ->
                allianceService.applyToAlliance(
                        applicant.userId(),
                        secondAlliance.getId()
                )
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
                );
    }

    @Test
    @DisplayName("같은 학교가 서로 다른 연합에 가입 신청할 수 있다")
    void applyToAlliance_allowsPendingInDifferentAlliances() {
        // given: 서로 다른 연합 2개 생성
        Alliance firstAlliance = createAlliance();

        TestMember otherFounder = createTestMember("otherFounder");

        Alliance secondAlliance = allianceService.createAlliance(
                otherFounder.userId(),
                "다른테스트연합",
                24
        );

        // when: 같은 학교가 두 연합에 각각 가입 신청
        AllianceUniversity firstApplication =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        firstAlliance.getId()
                );

        AllianceUniversity secondApplication =
                allianceService.applyToAlliance(
                        applicant.userId(),
                        secondAlliance.getId()
                );

        entityManager.flush();
        entityManager.clear();

        // then: 두 신청 모두 PENDING
        assertThat(firstApplication.getId())
                .isNotEqualTo(secondApplication.getId());

        assertThat(findStatus(firstApplication.getId()))
                .isEqualTo("PENDING");

        assertThat(findStatus(secondApplication.getId()))
                .isEqualTo("PENDING");

        // 각각 다른 연합에 신청이 저장되었는지 확인
        assertThat(countByStatus(firstAlliance.getId(), "PENDING"))
                .isEqualTo(1L);

        assertThat(countByStatus(secondAlliance.getId(), "PENDING"))
                .isEqualTo(1L);
    }

    // ===== 공통 테스트 데이터 생성 =====

    private Alliance createAlliance() {
        return allianceService.createAlliance(
                founder.userId(),
                "테스트연합",
                24
        );
    }

    private TestMember createTestMember(String type) {
        String suffix =
                UUID.randomUUID().toString().substring(0, 8);

        // 1. 테스트 대학교 생성
        Long universityId = jdbcTemplate.queryForObject(
                """
                INSERT INTO university (name)
                VALUES (?)
                RETURNING id
                """,
                Long.class,
                "연합통합_" + type + "_" + suffix
        );

        // 2. 테스트 학과 생성
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

        // 3. 테스트 회원 생성
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

        User savedUser = userRepository.saveAndFlush(user);

        return new TestMember(
                savedUser.getId(),
                universityId
        );
    }

    // ===== PostgreSQL 조회 헬퍼 =====

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

    private Long countByStatus(Long allianceId, String status) {
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

    private record TestMember(
            Long userId,
            Long universityId
    ) {
    }
}