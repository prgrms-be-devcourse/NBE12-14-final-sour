package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchSettlementCompletionService;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
class AllianceMatchSettlementCompletionIntegrationTest {

    @Autowired
    private AllianceRepository allianceRepository;

    @Autowired
    private AllianceMatchRepository matchRepository;

    @Autowired
    private AllianceMatchSettlementRepository settlementRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private AllianceMatchSettlementCompletionService completionService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Redis 정산 적용 후 대결 종료와 정산 완료를 DB에 함께 저장한다")
    void completeApplied_persistsMatchAndSettlement() {
        // given: 종료 시각이 지난 대결
        OffsetDateTime startedAt = OffsetDateTime.now().minusHours(7);

        Alliance allianceA = allianceRepository.saveAndFlush(
                Alliance.create(
                        "A_" + UUID.randomUUID(),
                        72,
                        startedAt.minusHours(1)
                )
        );

        Alliance allianceB = allianceRepository.saveAndFlush(
                Alliance.create(
                        "B_" + UUID.randomUUID(),
                        72,
                        startedAt.minusHours(1)
                )
        );

        allianceA.startMatch(startedAt);
        allianceB.startMatch(startedAt);

        AllianceMatch match = AllianceMatch.request(
                allianceA.getId(),
                allianceB.getId(),
                6,
                startedAt.plusHours(1)
        );

        match.accept(startedAt);
        match = matchRepository.saveAndFlush(match);

        // given: 정산 대상 학교 4곳
        University a1 = universityRepository.save(
                new University("A1_" + UUID.randomUUID())
        );
        University a2 = universityRepository.save(
                new University("A2_" + UUID.randomUUID())
        );
        University b1 = universityRepository.save(
                new University("B1_" + UUID.randomUUID())
        );
        University b2 = universityRepository.save(
                new University("B2_" + UUID.randomUUID())
        );

        // given: Redis 정산 적용이 확인된 이력
        AllianceMatchSettlement settlement =
                AllianceMatchSettlement.prepare(
                        match.getId(),
                        300L, 200L,
                        a1.getId(), a2.getId(),
                        b1.getId(), b2.getId(),
                        120L, 180L, 80L, 120L
                );

        settlement.markApplied();
        settlementRepository.saveAndFlush(settlement);

        Long matchId = match.getId();
        Long allianceAId = allianceA.getId();
        Long allianceBId = allianceB.getId();

        entityManager.flush();
        entityManager.clear();

        // when: 대결 종료 및 정산 완료
        completionService.completeApplied(matchId);

        entityManager.flush();
        entityManager.clear();

        // then: 대결 종료 결과 확인
        AllianceMatch finishedMatch = matchRepository
                .findById(matchId)
                .orElseThrow();

        assertThat(finishedMatch.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(finishedMatch.getAScore()).isEqualTo(300L);
        assertThat(finishedMatch.getBScore()).isEqualTo(200L);
        assertThat(finishedMatch.getWinnerAllianceId())
                .isEqualTo(allianceAId);

        // then: 정산 이력 COMPLETED
        AllianceMatchSettlement savedSettlement = settlementRepository
                .findById(matchId)
                .orElseThrow();

        assertThat(savedSettlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.COMPLETED);

        // then: 양쪽 연합 ACTIVE
        assertThat(allianceRepository.findById(allianceAId)
                .orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);

        assertThat(allianceRepository.findById(allianceBId)
                .orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);

        // when: 동일한 종료 작업 재시도
        completionService.completeApplied(matchId);

        // then: 이미 완료된 대결 결과 유지
        assertThat(matchRepository.findById(matchId)
                .orElseThrow().getWinnerAllianceId())
                .isEqualTo(allianceAId);
    }
}