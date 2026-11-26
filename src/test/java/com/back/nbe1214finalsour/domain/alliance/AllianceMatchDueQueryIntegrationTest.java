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

    @Test
    @DisplayName("신청 마감 시각이 지난 대기 중 대결만 신청 만료 대상으로 조회한다")
    void findExpiredRequestIds_returnsOnlyExpiredRequestedMatches() {
        // given
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        AllianceMatch expired = createMatch(now.minusHours(2), now, false, now.minusMinutes(1));
        AllianceMatch atDeadline = createMatch(now.minusHours(2), now, false, now);
        AllianceMatch waiting = createMatch(now, now, false, now.plusHours(1));
        AllianceMatch canceled = createMatch(now.minusHours(2), now, false, now.minusMinutes(1));
        canceled.cancelRequested();
        allianceMatchRepository.saveAndFlush(canceled);
        AllianceMatch ongoing = createMatch(now.minusHours(2), now, true, now.minusMinutes(1));

        // when
        List<Long> result = allianceMatchRepository.findExpiredRequestIds(now);

        // then
        assertThat(result)
                .containsExactly(expired.getId(), atDeadline.getId());
    }

    private AllianceMatch createMatch(
            OffsetDateTime startedAt,
            OffsetDateTime now,
            boolean accepted
    ) {
        return createMatch(startedAt, now, accepted, now.plusHours(1));
    }

    private AllianceMatch createMatch(
            OffsetDateTime startedAt,
            OffsetDateTime now,
            boolean accepted,
            OffsetDateTime requestExpiredAt
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
                requestExpiredAt
        );

        if (accepted) {
            match.accept(startedAt);
        }

        return allianceMatchRepository.saveAndFlush(match);
    }
}