package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchSettlementCompletionService;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlement;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class AllianceMatchFinishIntegrationTest {

    @Autowired
    private AllianceRepository allianceRepository;

    @Autowired
    private AllianceMatchRepository allianceMatchRepository;

    @Autowired
    private AllianceMatchSettlementCompletionService completionService;

    @Autowired
    private AllianceMatchSettlementRepository settlementRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("대결 종료 결과와 양쪽 연합 상태가 DB에 저장된다")
    void finishMatchAfterSettlement_persistsResult() {
        // given: 종료 시각이 지난 대결
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);

        Alliance allianceA = allianceRepository.saveAndFlush(
                Alliance.create("종료검증A", 72, startedAt.minusHours(1))
        );

        Alliance allianceB = allianceRepository.saveAndFlush(
                Alliance.create("종료검증B", 72, startedAt.minusHours(1))
        );

        AllianceMatch match = AllianceMatch.request(
                allianceA.getId(),
                allianceB.getId(),
                6,
                startedAt.plusHours(1)
        );

        match.accept(startedAt);
        allianceA.startMatch(startedAt);
        allianceB.startMatch(startedAt);

        allianceMatchRepository.saveAndFlush(match);

        Long matchId = match.getId();
        Long allianceAId = allianceA.getId();
        Long allianceBId = allianceB.getId();

        entityManager.flush();
        entityManager.clear();

        // given: Redis 적용이 확인된 정산 스냅샷
        University a1 = universityRepository.save(new University("종료A1_" + UUID.randomUUID()));
        University a2 = universityRepository.save(new University("종료A2_" + UUID.randomUUID()));
        University b1 = universityRepository.save(new University("종료B1_" + UUID.randomUUID()));
        University b2 = universityRepository.save(new University("종료B2_" + UUID.randomUUID()));
        AllianceMatchSettlement settlement = AllianceMatchSettlement.prepare(
                matchId, 300L, 200L,
                a1.getId(), a2.getId(), b1.getId(), b2.getId(),
                120L, 180L, 80L, 120L);
        settlement.markApplied();
        settlementRepository.saveAndFlush(settlement);
        entityManager.flush();
        entityManager.clear();

        // when: 안전한 DB 종료/정산 이력 완료 경로
        completionService.completeApplied(matchId);

        entityManager.flush();
        entityManager.clear();

        // then: DB에서 대결 종료 결과 재조회
        AllianceMatch finishedMatch = allianceMatchRepository
                .findById(matchId)
                .orElseThrow();

        assertThat(finishedMatch.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(finishedMatch.getAScore()).isEqualTo(300L);
        assertThat(finishedMatch.getBScore()).isEqualTo(200L);
        assertThat(finishedMatch.getWinnerAllianceId())
                .isEqualTo(allianceAId);

        // then: 두 연합의 상태도 DB에 반영됨
        Alliance savedA = allianceRepository.findById(allianceAId)
                .orElseThrow();

        Alliance savedB = allianceRepository.findById(allianceBId)
                .orElseThrow();

        assertThat(savedA.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);

        assertThat(savedB.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
    }
}