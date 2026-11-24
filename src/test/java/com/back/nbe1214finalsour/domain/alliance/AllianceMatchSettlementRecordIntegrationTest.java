package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@Transactional
class AllianceMatchSettlementRecordIntegrationTest {
    @Autowired AllianceRepository allianceRepository;
    @Autowired AllianceMatchRepository matchRepository;
    @Autowired AllianceMatchSettlementRepository settlementRepository;
    @Autowired UniversityRepository universityRepository;
    @PersistenceContext EntityManager entityManager;

    @Test
    @DisplayName("정산 스냅샷이 PENDING 상태로 DB 저장된다")
    void prepare_persistsSettlementSnapshot() {
        AllianceMatchSettlement s = createSettlement();
        Long id = s.getMatchId();
        entityManager.flush();
        entityManager.clear();
        AllianceMatchSettlement saved = settlementRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
        assertThat(saved.getAScore()).isEqualTo(300L);
        assertThat(saved.getBScore()).isEqualTo(200L);
        assertThat(saved.getAUniversity1Contribution()).isEqualTo(120L);
        assertThat(saved.getBUniversity2Contribution()).isEqualTo(120L);
    }

    @Test
    @DisplayName("같은 대결에 정산 이력 중복 INSERT는 DB가 거부한다")
    void prepare_rejectsDuplicateMatchId() {
        Long id = createSettlement().getMatchId();
        entityManager.flush();
        entityManager.clear();
        // PostgreSQL 오류 이후 트랜잭션이 실패 상태가 되므로 이 테스트 마지막에서만 실행한다.
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                INSERT INTO alliance_match_settlement (
                  match_id, a_score, b_score,
                  a_university_1_id, a_university_2_id,
                  b_university_1_id, b_university_2_id,
                  a_university_1_contribution, a_university_2_contribution,
                  b_university_1_contribution, b_university_2_contribution, status)
                SELECT match_id, a_score, b_score,
                  a_university_1_id, a_university_2_id,
                  b_university_1_id, b_university_2_id,
                  a_university_1_contribution, a_university_2_contribution,
                  b_university_1_contribution, b_university_2_contribution, status
                FROM alliance_match_settlement WHERE match_id = :id
                """).setParameter("id", id).executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    private AllianceMatchSettlement createSettlement() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(7);
        Alliance a = allianceRepository.saveAndFlush(Alliance.create("A_" + UUID.randomUUID(),72,start.minusHours(1)));
        Alliance b = allianceRepository.saveAndFlush(Alliance.create("B_" + UUID.randomUUID(),72,start.minusHours(1)));
        AllianceMatch match = AllianceMatch.request(a.getId(), b.getId(),6,start.plusHours(1));
        match.accept(start);
        match = matchRepository.saveAndFlush(match);
        University a1 = universityRepository.save(new University("A1_" + UUID.randomUUID()));
        University a2 = universityRepository.save(new University("A2_" + UUID.randomUUID()));
        University b1 = universityRepository.save(new University("B1_" + UUID.randomUUID()));
        University b2 = universityRepository.save(new University("B2_" + UUID.randomUUID()));
        return settlementRepository.saveAndFlush(AllianceMatchSettlement.prepare(
                match.getId(),300L,200L,a1.getId(),a2.getId(),b1.getId(),b2.getId(),
                120L,180L,80L,120L));
    }
}
