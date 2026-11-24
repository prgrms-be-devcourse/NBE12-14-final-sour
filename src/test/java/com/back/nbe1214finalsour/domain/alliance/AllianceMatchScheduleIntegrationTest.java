package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class AllianceMatchScheduleIntegrationTest {
    @Autowired AllianceRepository allianceRepository;
    @Autowired AllianceMatchRepository matchRepository;
    @Autowired EntityManager entityManager;

    @Test
    void scheduledMatchIsPersistedAndDueQueryFindsIt() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime start = now.plusHours(3);
        Alliance a = allianceRepository.saveAndFlush(Alliance.create("예약A", 48, now));
        Alliance b = allianceRepository.saveAndFlush(Alliance.create("예약B", 48, now));
        AllianceMatch match = AllianceMatch.request(a.getId(), b.getId(), 2,
                now.plusHours(1), start);
        match.schedule(now);
        Long id = matchRepository.saveAndFlush(match).getId();
        entityManager.clear();
        AllianceMatch found = matchRepository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(AllianceMatchStatus.SCHEDULED);
        assertThat(found.getScheduledStartAt().toEpochSecond()).isEqualTo(start.toEpochSecond());
        assertThat(found.getStartedAt()).isNull();
        assertThat(matchRepository.existsScheduledForAlliance(a.getId())).isTrue();
        assertThat(matchRepository.existsOpenMatchForAlliance(b.getId())).isTrue();
        assertThat(matchRepository.findDueScheduledIds(now)).doesNotContain(id);
        assertThat(matchRepository.findDueScheduledIds(start.plusSeconds(1))).contains(id);
    }
}
