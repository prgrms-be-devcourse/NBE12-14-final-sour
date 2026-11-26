package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.chat.repository.AllianceChatRoomRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class AllianceIntegrationTest {

    @Autowired
    private AllianceService allianceService;

    @Autowired
    private AllianceQueryService allianceQueryService;

    @Autowired
    private AllianceChatRoomRepository allianceChatRoomRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long userId;
    private Long universityId;

    @BeforeEach
    void setUp() {

        // 1. 테스트용 대학교 생성
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id",
                Long.class,
                "연합테스트대학교_" + UUID.randomUUID()
        );

        // 2. 테스트용 학과 생성
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

        // 3. 테스트용 회원 생성
        String email = "alliance-" + UUID.randomUUID() + "@test.ac.kr";

        User user = User.create(
                universityId,
                departmentId,
                "연합테스트회원",
                email,
                emailHasher.hash(email),
                passwordEncoder.encode("Passw0rd!"),
                OffsetDateTime.now()
        );

        // 4. 회원 DB 저장
        User savedUser = userRepository.saveAndFlush(user);

        userId = savedUser.getId();
    }

    @Test
    @DisplayName("연합 생성 시 연합과 최초 가입 학교가 DB에 저장된다")
    void createAlliance_persistsAllianceAndFounder() {

        // when
        Alliance alliance = allianceService.createAlliance(
                userId,
                "테스트연합",
                24
        );

        Long allianceId = alliance.getId();

        allianceChatRoomRepository.flush();
        assertThat(allianceChatRoomRepository.findById(allianceId).orElseThrow().isNew()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT alliance_name FROM alliance_chat_room WHERE alliance_id = ?",
                String.class, allianceId)).isEqualTo(alliance.getName());
        entityManager.clear();
        assertThat(allianceChatRoomRepository.findById(allianceId).orElseThrow().isNew()).isFalse();

        // then 1. 연합 정보 확인
        Alliance savedAlliance =
                allianceQueryService.getAlliance(allianceId);

        assertThat(savedAlliance.getName())
                .isEqualTo("테스트연합");

        assertThat(savedAlliance.getDurationHours())
                .isEqualTo(24);

        assertThat(savedAlliance.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);

        assertThat(savedAlliance.getTotalScore())
                .isEqualTo(0L);

        // then 2. 최초 학교 자동 가입 확인
        List<AllianceUniversity> members =
                allianceQueryService.getAcceptedUniversities(allianceId);

        assertThat(members).hasSize(1);

        AllianceUniversity founder = members.get(0);

        assertThat(founder.getUniversityId())
                .isEqualTo(universityId);

        assertThat(founder.getApplicantUserId())
                .isEqualTo(userId);

        assertThat(founder.getApproverUserId())
                .isEqualTo(userId);

        assertThat(founder.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);

        // then 3. PostgreSQL에 실제 저장된 데이터 확인
        Long allianceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alliance WHERE id = ?",
                Long.class,
                allianceId
        );

        Long memberCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM alliance_university
                WHERE alliance_id = ?
                  AND university_id = ?
                  AND status = 'ACCEPTED'
                """,
                Long.class,
                allianceId,
                universityId
        );

        assertThat(allianceCount).isEqualTo(1L);
        assertThat(memberCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 연합에 가입한 학교는 새 연합을 생성할 수 없다")
    void createAlliance_rejectsAlreadyJoinedUniversity() {

        // given: 첫 번째 연합 생성
        Alliance firstAlliance = allianceService.createAlliance(
                userId,
                "첫번째연합",
                24
        );

        assertThat(firstAlliance.getId()).isNotNull();

        // when & then: 같은 학교에서 두 번째 연합 생성 시도
        assertThatThrownBy(() ->
                allianceService.createAlliance(
                        userId,
                        "두번째연합",
                        24
                )
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
                );

        // DB 검증 1: 두 번째 연합은 저장되지 않아야 한다.
        Long secondAllianceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alliance WHERE name = ?",
                Long.class,
                "두번째연합"
        );

        assertThat(secondAllianceCount).isEqualTo(0L);

        // DB 검증 2: 해당 학교의 가입 이력은 1건이어야 한다.
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
    }
}
