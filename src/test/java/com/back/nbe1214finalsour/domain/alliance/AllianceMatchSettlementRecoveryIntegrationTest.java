package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.alliance.service.*;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AllianceMatchSettlementRecoveryIntegrationTest {
    @Autowired AllianceRepository allianceRepository;
    @Autowired AllianceMatchRepository matchRepository;
    @Autowired AllianceMatchSettlementRepository settlementRepository;
    @Autowired UniversityRepository universityRepository;
    @Autowired AllianceMatchRedisSettlementService redisSettlementService;
    @Autowired AllianceMatchSettlementApplicationService applicationService;
    @Autowired AllianceMatchSettlementOrchestrator orchestrator;
    @Autowired StringRedisTemplate redis;
    @Autowired TransactionTemplate transactionTemplate;

    private Fixture fixture;

    record Fixture(Long matchId, Long aId, Long bId, Long a1, Long a2, Long b1, Long b2) {
        List<Long> universities() { return List.of(a1,a2,b1,b2); }
    }

    @Test
    @DisplayName("Lua 성공 후 DB 상태 기록 중단이면 재시도하여 지급 중복 없이 완료")
    void recover_whenRedisAppliedButDbPending() {
        fixture = createFixture();
        seedRedis();
        // Redis는 적용, DB는 PENDING (프로세스가 이 사이에 종료됐다고 가정)
        assertThat(redisSettlementService.settleOnce(fixture.matchId(),
                fixture.b1(), fixture.b2(), fixture.a1(), fixture.a2(), 80L,120L)).isTrue();
        assertThat(status()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
        orchestrator.settleAndComplete(fixture.matchId());
        assertCompletedAndScores();
        orchestrator.settleAndComplete(fixture.matchId());
        assertCompletedAndScores();
    }

    @Test
    @DisplayName("APPLIED 이후 DB 종료 작업 실패면 재시도하여 완료")
    void recover_whenAppliedButMatchInProgress() {
        fixture = createFixture();
        seedRedis();
        applicationService.applyPending(fixture.matchId());
        assertThat(status()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
        assertThat(matchRepository.findById(fixture.matchId()).orElseThrow().getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        orchestrator.settleAndComplete(fixture.matchId());
        assertCompletedAndScores();
    }

    private Fixture createFixture() {
        return transactionTemplate.execute(tx -> {
            OffsetDateTime start = OffsetDateTime.now().minusHours(7);
            Alliance a = allianceRepository.saveAndFlush(Alliance.create("A_"+UUID.randomUUID(),72,start.minusHours(1)));
            Alliance b = allianceRepository.saveAndFlush(Alliance.create("B_"+UUID.randomUUID(),72,start.minusHours(1)));
            a.startMatch(start);
            b.startMatch(start);
            AllianceMatch match = AllianceMatch.request(a.getId(),b.getId(),6,start.plusHours(1));
            match.accept(start);
            match = matchRepository.saveAndFlush(match);
            University a1 = saveUniversity(); University a2 = saveUniversity();
            University b1 = saveUniversity(); University b2 = saveUniversity();
            settlementRepository.saveAndFlush(AllianceMatchSettlement.prepare(
                    match.getId(), 300L,200L,a1.getId(),a2.getId(),b1.getId(),b2.getId(),
                    120L,180L,80L,120L));
            return new Fixture(match.getId(),a.getId(),b.getId(),a1.getId(),a2.getId(),b1.getId(),b2.getId());
        });
    }

    private University saveUniversity() {
        return universityRepository.saveAndFlush(new University("정산_"+UUID.randomUUID()));
    }

    private void seedRedis() {
        setScore(fixture.a1(), 5); setScore(fixture.a2(),10);
        setScore(fixture.b1(),70); setScore(fixture.b2(),500);
    }

    private void setScore(Long id, double score) {
        redis.opsForZSet().add(RedisKey.RANK_UNIV.of(),String.valueOf(id),score);
    }

    private Double score(Long id) {
        return redis.opsForZSet().score(RedisKey.RANK_UNIV.of(),String.valueOf(id));
    }

    private AllianceMatchSettlementStatus status() {
        return settlementRepository.findById(fixture.matchId()).orElseThrow().getStatus();
    }

    private void assertCompletedAndScores() {
        AllianceMatch match = matchRepository.findById(fixture.matchId()).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.FINISHED);
        assertThat(match.getWinnerAllianceId()).isEqualTo(fixture.aId());
        assertThat(status()).isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
        assertThat(allianceRepository.findById(fixture.aId()).orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
        assertThat(allianceRepository.findById(fixture.bId()).orElseThrow().getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
        assertThat(score(fixture.b1())).isEqualTo(0.0);
        assertThat(score(fixture.b2())).isEqualTo(380.0);
        assertThat(score(fixture.a1())).isEqualTo(100.0);
        assertThat(score(fixture.a2())).isEqualTo(105.0);
    }

    @AfterEach
    void cleanUp() {
        if (fixture == null) return;
        for (Long id : fixture.universities()) {
            String member = String.valueOf(id);
            redis.opsForZSet().remove(RedisKey.RANK_UNIV.of(), member);
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of(),member);
            redis.opsForSet().remove(RedisKey.DIRTY_UNIV.of()+":flushing",member);
        }
        redis.delete("match:"+fixture.matchId()+":settled");
        transactionTemplate.executeWithoutResult(tx -> {
            settlementRepository.deleteById(fixture.matchId());
            settlementRepository.flush();
            matchRepository.deleteById(fixture.matchId());
            matchRepository.flush();
            allianceRepository.deleteAllById(List.of(fixture.aId(),fixture.bId()));
            allianceRepository.flush();
            universityRepository.deleteAllById(fixture.universities());
            universityRepository.flush();
        });
    }
}
