package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
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
class AllianceMatchIntegrationTest {

    @Autowired
    private AllianceRepository allianceRepository;

    @Autowired
    private AllianceMatchRepository allianceMatchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("대결 신청 정보가 DB에 저장되고 조회된다")
    void request_persistsMatch() {
        // given: 두 연합 생성
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = allianceRepository.saveAndFlush(
                Alliance.create("테스트연합A", 48, now)
        );

        Alliance allianceB = allianceRepository.saveAndFlush(
                Alliance.create("테스트연합B", 48, now)
        );

        // given: 대결 신청 생성
        AllianceMatch match = AllianceMatch.request(
                allianceA.getId(),
                allianceB.getId(),
                6,
                now.plusHours(12)
        );

        // when: DB에 저장
        AllianceMatch savedMatch =
                allianceMatchRepository.saveAndFlush(match);

        Long matchId = savedMatch.getId();

        // 영속성 컨텍스트 초기화
        entityManager.clear();

        // then: DB에서 다시 조회
        AllianceMatch foundMatch =
                allianceMatchRepository.findById(matchId)
                        .orElseThrow();

        assertThat(foundMatch.getAllianceAId())
                .isEqualTo(allianceA.getId());

        assertThat(foundMatch.getAllianceBId())
                .isEqualTo(allianceB.getId());

        assertThat(foundMatch.getDurationHours())
                .isEqualTo(6);

        assertThat(foundMatch.getStatus())
                .isEqualTo(AllianceMatchStatus.REQUESTED);

        assertThat(foundMatch.getStartedAt()).isNull();
        assertThat(foundMatch.getEndedAt()).isNull();

        assertThat(foundMatch.getAScore()).isZero();
        assertThat(foundMatch.getBScore()).isZero();

        assertThat(foundMatch.getCreatedAt()).isNotNull();
    }
}