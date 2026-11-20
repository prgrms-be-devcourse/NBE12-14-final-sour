package com.back.nbe1214finalsour.domain.alliance.repository;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AllianceMatchRepository extends JpaRepository<AllianceMatch, Long> {

    @Query("""
        SELECT m.id FROM AllianceMatch m
        WHERE m.deletedAt IS NULL AND m.status = com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.REQUESTED
          AND (m.allianceAId = :allianceId OR m.allianceBId = :allianceId)
        ORDER BY m.id
        """)
    List<Long> findRequestedIdsForAlliance(@Param("allianceId") Long allianceId);

    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM AllianceMatch m
        WHERE m.deletedAt IS NULL AND m.status IN (
          com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.REQUESTED,
          com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.SCHEDULED,
          com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.IN_PROGRESS)
          AND (m.allianceAId = :allianceId OR m.allianceBId = :allianceId)
        """)
    boolean existsOpenMatchForAlliance(@Param("allianceId") Long allianceId);

    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
        FROM AllianceMatch m
        WHERE m.status = com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.REQUESTED
          AND m.deletedAt IS NULL
          AND ((m.allianceAId = :a AND m.allianceBId = :b)
            OR (m.allianceAId = :b AND m.allianceBId = :a))
        """)
    boolean existsRequestedBetween(@Param("a") Long allianceAId,
                                   @Param("b") Long allianceBId);

    // 엔티티를 영속성 컨텍스트에 넣지 않고 FK 값 두 개만 읽는다.
    // 두 연합에 락을 건 다음에야 findByIdForUpdate()로 대결 엔티티를 조회한다.
    @Query("""
        SELECT new com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchPair(
            m.allianceAId, m.allianceBId)
        FROM AllianceMatch m
        WHERE m.id = :matchId
        """)
    Optional<AllianceMatchPair> findPairById(@Param("matchId") Long matchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM AllianceMatch m WHERE m.id = :matchId")
    Optional<AllianceMatch> findByIdForUpdate(@Param("matchId") Long matchId);

    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM AllianceMatch m
        WHERE m.deletedAt IS NULL AND m.status = com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus.SCHEDULED
          AND (m.allianceAId = :allianceId OR m.allianceBId = :allianceId)
        """)
    boolean existsScheduledForAlliance(@Param("allianceId") Long allianceId);

    @Query(value = """
        SELECT id FROM alliance_match
        WHERE status = 'SCHEDULED' AND deleted_at IS NULL
          AND scheduled_start_at <= :now
        ORDER BY id ASC LIMIT 100
        """, nativeQuery = true)
    List<Long> findDueScheduledIds(@Param("now") OffsetDateTime now);

    // 주기적으로 만료되는 신청은 작은 묶음으로 처리해 긴 트랜잭션을 방지함.
    @Query(value = """
        SELECT id FROM alliance_match
        WHERE status = 'REQUESTED' AND deleted_at IS NULL
          AND request_expired_at <= :now
        ORDER BY id ASC LIMIT 100
        """, nativeQuery = true)
    List<Long> findExpiredRequestIds(@Param("now") OffsetDateTime now);

    @Query(value = """
        SELECT id FROM alliance_match
        WHERE score_state = 'PENDING' AND status = 'IN_PROGRESS' AND deleted_at IS NULL
          AND ended_at > :now
        ORDER BY id ASC LIMIT 100
        """, nativeQuery = true)
    List<Long> findMatchesAwaitingScorePreparation(@Param("now") OffsetDateTime now);

    @Query(value = """
        SELECT id FROM alliance_match
        WHERE score_state = 'READY' AND status = 'IN_PROGRESS' AND deleted_at IS NULL
          AND ended_at <= :now AND id > :afterId
        ORDER BY id LIMIT 100
        """, nativeQuery = true)
    List<Long> findMatchesAwaitingScoreFinalization(@Param("now") OffsetDateTime now, @Param("afterId") long afterId);

    // 종료 시각이 지난 진행 중 대결만 조회한다.
    // 한 번에 최대 100건씩 처리해 긴 트랜잭션을 방지한다.
    @Query(value = """
        SELECT id FROM alliance_match
        WHERE status = 'IN_PROGRESS' AND deleted_at IS NULL
          AND ended_at <= :now
        ORDER BY id ASC LIMIT 100
        """, nativeQuery = true)
    List<Long> findDueInProgressIds(@Param("now") OffsetDateTime now);

}
