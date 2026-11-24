package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.alliance.service.*;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class AllianceMatchFlowIntegrationTest {
    @Autowired AllianceMatchService matchService;
    @Autowired AllianceService allianceService;
    @Autowired AllianceRepository allianceRepository;
    @Autowired AllianceMatchRepository matchRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailHasher hasher;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager em;

    record Member(Long userId, Long universityId) {}
    Member a1, a2, b1, b2;

    @BeforeEach
    void setUp() {
        a1 = createMember(); a2 = createMember();
        b1 = createMember(); b2 = createMember();
    }

    private Member createMember() {
        String key = UUID.randomUUID().toString().replace("-", "");
        Long uniId = jdbc.queryForObject("INSERT INTO university(name) VALUES(?) RETURNING id",
                Long.class, "연합대결테스트_" + key);
        Long deptId = jdbc.queryForObject("INSERT INTO department(university_id, name) VALUES (?, ?) RETURNING id",
                Long.class, uniId, "테스트학과");
        String email = "match-" + key + "@test.ac.kr";
        User user = User.create(uniId, deptId, "대결" + key.substring(0, 10),
                email, hasher.hash(email), encoder.encode("Passw0rd!"), OffsetDateTime.now());
        return new Member(userRepository.saveAndFlush(user).getId(), uniId);
    }

    private Alliance[] makePairedAlliances() {
        Alliance a = allianceService.createAlliance(a1.userId(), "대결연합A", 48);
        Alliance b = allianceService.createAlliance(b1.userId(), "대결연합B", 48);
        AllianceUniversity appA = allianceService.applyToAlliance(a2.userId(), a.getId());
        AllianceUniversity appB = allianceService.applyToAlliance(b2.userId(), b.getId());
        allianceService.approveApplication(a1.userId(), appA.getId());
        allianceService.approveApplication(b1.userId(), appB.getId());
        return new Alliance[]{a, b};
    }

    @Test
    void request_accept_persistsRealPostgresRows() {
        Alliance[] pair = makePairedAlliances();
        AllianceMatch requested = matchService.requestMatch(a1.userId(), pair[0].getId(), pair[1].getId(), 6);
        Long id = requested.getId();
        assertThat(id).isNotNull();
        AllianceMatch started = matchService.acceptMatch(b1.userId(), id);
        assertThat(started.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        em.flush();
        em.clear();
        AllianceMatch loaded = matchRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(loaded.getEndedAt()).isEqualTo(loaded.getStartedAt().plusHours(6));
        assertThat(loadRoster(id)).containsExactly(a1.universityId(), a2.universityId(),
                b1.universityId(), b2.universityId());
        assertThat(allianceRepository.findById(pair[0].getId()).orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.IN_MATCH);
        assertThat(allianceRepository.findById(pair[1].getId()).orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.IN_MATCH);
    }

    @Test
    void scheduledMatchFreezesRosterAtActualStartOnly() {
        Alliance[] pair = makePairedAlliances();
        OffsetDateTime scheduled = OffsetDateTime.now().plusHours(1);
        Long id = matchService.requestMatch(a1.userId(), pair[0].getId(), pair[1].getId(), 6, scheduled).getId();
        assertThat(matchService.acceptMatch(b1.userId(), id).getStatus()).isEqualTo(AllianceMatchStatus.SCHEDULED);
        em.flush();
        assertThat(loadRoster(id)).containsOnlyNulls();
        matchService.startScheduledMatch(id);
        assertThat(loadRoster(id)).containsOnlyNulls();

        // 시계를 기다리지 않고 시작 워커에 마감이 지난 예약을 넘긴다.
        OffsetDateTime due = OffsetDateTime.now().minusMinutes(1);
        jdbc.update("UPDATE alliance_match SET scheduled_start_at = ? WHERE id = ?", due, id);
        em.clear();
        matchService.startScheduledMatch(id);
        em.flush();
        em.clear();

        AllianceMatch started = matchService.getMatch(id);
        OffsetDateTime actual = started.getStartedAt();
        assertThat(started.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(actual).isAfter(due);
        assertThat(started.getEndedAt()).isEqualTo(actual.plusHours(6));
        assertThat(loadRoster(id)).containsExactly(a1.universityId(), a2.universityId(),
                b1.universityId(), b2.universityId());

        matchService.startScheduledMatch(id);
        em.flush();
        em.clear();
        assertThat(matchService.getMatch(id).getStartedAt()).isEqualTo(actual);
        assertThat(loadRoster(id)).containsExactly(a1.universityId(), a2.universityId(),
                b1.universityId(), b2.universityId());
    }

    private List<Long> loadRoster(Long id) {
        return jdbc.queryForObject("""
                SELECT a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id
                FROM alliance_match WHERE id = ?
                """, (rs, _) -> Arrays.asList(rs.getObject(1, Long.class), rs.getObject(2, Long.class),
                rs.getObject(3, Long.class), rs.getObject(4, Long.class)), id);
    }

    @Test
    void request_cancel_then_canRequestAgain() {
        Alliance[] pair = makePairedAlliances();
        AllianceMatch first = matchService.requestMatch(a1.userId(), pair[0].getId(), pair[1].getId(), 6);
        matchService.cancelMatch(a1.userId(), first.getId());
        em.flush();
        AllianceMatch again = matchService.requestMatch(a1.userId(), pair[0].getId(), pair[1].getId(), 6);
        assertThat(again.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
        assertThat(again.getId()).isNotEqualTo(first.getId());
    }
}
