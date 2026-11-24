package com.back.nbe1214finalsour.domain.alliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/** 내 학교의 현재 연합 (명세 GET /api/v1/alliances/me, SCRUM-182). 연합 채팅 소속 판정도 이 조회를 쓴다 */
@IntegrationTest
@Transactional
class AllianceCurrentIntegrationTest {

    @Autowired
    private AllianceService allianceService;
    @Autowired
    private AllianceQueryService allianceQueryService;
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

    private Member founder;
    private Member applicant;

    @BeforeEach
    void setUp() {
        founder = member("founder");
        applicant = member("applicant");
    }

    @Test
    @DisplayName("승인된 학교만 소속이다: 만든 학교는 바로, 신청한 학교는 승인 뒤부터")
    void acceptedUniversityBelongs() {
        Alliance alliance = allianceService.createAlliance(founder.userId(), "현재연합", 24);
        AllianceUniversity application = allianceService.applyToAlliance(applicant.userId(), alliance.getId());
        flush();

        assertThat(currentId(founder)).contains(alliance.getId());
        assertThat(currentId(applicant)).isEmpty();

        allianceService.approveApplication(founder.userId(), application.getId());
        flush();
        assertThat(currentId(applicant)).contains(alliance.getId());
    }

    @Test
    @DisplayName("탈퇴(LEFT)·소속 행 삭제는 소속이 아니다")
    void leftOrDeletedMembershipDoesNotBelong() {
        Alliance alliance = allianceService.createAlliance(founder.userId(), "탈퇴연합", 24);
        flush();

        jdbcTemplate.update("UPDATE alliance_university SET status = 'LEFT' WHERE alliance_id = ?", alliance.getId());
        assertThat(currentId(founder)).isEmpty();

        jdbcTemplate.update("UPDATE alliance_university SET status = 'ACCEPTED', deleted_at = now() WHERE alliance_id = ?",
                alliance.getId());
        assertThat(currentId(founder)).isEmpty();
    }

    @Test
    @DisplayName("만료 시각이 지났거나 EXPIRED·삭제된 연합은 소속이 아니다 (만료 스케줄러가 돌기 전 포함)")
    void expiredOrDeletedAllianceDoesNotBelong() {
        Alliance alliance = allianceService.createAlliance(founder.userId(), "만료연합", 24);
        flush();
        assertThat(currentId(founder)).contains(alliance.getId());

        // ck_alliance_expired_at(expired_at > created_at)을 지키며 만료 시각만 지난 상태로 만든다
        jdbcTemplate.update("UPDATE alliance SET created_at = now() - interval '1 day', expired_at = now() - interval '1 second'"
                + " WHERE id = ?", alliance.getId());
        assertThat(currentId(founder)).isEmpty();

        jdbcTemplate.update("UPDATE alliance SET expired_at = now() + interval '1 hour', status = 'EXPIRED' WHERE id = ?",
                alliance.getId());
        assertThat(currentId(founder)).isEmpty();

        jdbcTemplate.update("UPDATE alliance SET status = 'IN_MATCH' WHERE id = ?", alliance.getId());
        assertThat(currentId(founder)).contains(alliance.getId());

        jdbcTemplate.update("UPDATE alliance SET deleted_at = now() WHERE id = ?", alliance.getId());
        assertThat(currentId(founder)).isEmpty();
    }

    @Test
    @DisplayName("학교 ID가 없으면 조회하지 않고 비어 있다")
    void noUniversity() {
        assertThat(allianceQueryService.findCurrentAlliance(null)).isEmpty();
    }

    private Optional<Long> currentId(Member member) {
        entityManager.clear();
        return allianceQueryService.findCurrentAlliance(member.universityId()).map(Alliance::getId);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Member member(String type) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long universityId = jdbcTemplate.queryForObject("INSERT INTO university (name) VALUES (?) RETURNING id",
                Long.class, "현재연합_" + type + "_" + suffix);
        Long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, ?) RETURNING id",
                Long.class, universityId, "테스트학과");
        String email = "alliance-current-" + UUID.randomUUID() + "@test.ac.kr";
        User user = userRepository.saveAndFlush(User.create(universityId, departmentId, "현재연합_" + suffix, email,
                emailHasher.hash(email), passwordEncoder.encode("Passw0rd!"), OffsetDateTime.now()));
        return new Member(user.getId(), universityId);
    }

    private record Member(Long userId, Long universityId) {
    }
}
