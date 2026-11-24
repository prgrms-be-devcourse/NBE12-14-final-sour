package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class AllianceMatchDueQueryIntegrationTest {

    @Autowired
    private AllianceRepository allianceRepository;

    @Autowired
    private AllianceMatchRepository allianceMatchRepository;

    @Test
    @DisplayName("종료 시각이 지난 진행 중 대결만 종료 대상으로 조회한다")
    void findDueInProgressIds_returnsOnlyDueMatches() {
        // given: 종료 시각이 지난 대결
        OffsetDateTime now = OffsetDateTime.now();

        AllianceMatch dueMatch = createMatch(
                now.minusHours(7),
                now,
                true
        );

        // given: 아직 종료 시각이 지나지 않은 대결
        AllianceMatch ongoingMatch = createMatch(
                now.minusHours(2),
                now,
                true
        );

        // given: 아직 수락되지 않은 대결
        AllianceMatch requestedMatch = createMatch(
                now,
                now,
                false
        );

        // when: 종료 대상 대결 ID 조회
        List<Long> result =
                allianceMatchRepository.findDueInProgressIds(now);

        // then: 종료 시각이 지난 진행 중 대결만 포함
        assertThat(result).contains(dueMatch.getId());
        assertThat(result).doesNotContain(ongoingMatch.getId());
        assertThat(result).doesNotContain(requestedMatch.getId());
    }

    private AllianceMatch createMatch(
            OffsetDateTime startedAt,
            OffsetDateTime now,
            boolean accepted
    ) {
        Alliance allianceA = allianceRepository.save(
                Alliance.create(
                        "A_" + UUID.randomUUID(),
                        72,
                        now.minusHours(8)
                )
        );

        Alliance allianceB = allianceRepository.save(
                Alliance.create(
                        "B_" + UUID.randomUUID(),
                        72,
                        now.minusHours(8)
                )
        );

        AllianceMatch match = AllianceMatch.request(
                allianceA.getId(),
                allianceB.getId(),
                6,
                now.plusHours(1)
        );

        if (accepted) {
            match.accept(startedAt);
        }

        return allianceMatchRepository.saveAndFlush(match);
    }
}