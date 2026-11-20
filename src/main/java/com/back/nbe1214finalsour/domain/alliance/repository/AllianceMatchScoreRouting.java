package com.back.nbe1214finalsour.domain.alliance.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class AllianceMatchScoreRouting {
    private final JdbcTemplate jdbc;

    public AllianceMatchScoreRouting(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Route(long matchId, OffsetDateTime startedAt, OffsetDateTime endedAt,
                        boolean ready, List<String> roster) {}

    // Exclusive start and shared submissions use the same school locks. Ordinary submissions do not block one another.
    public void lockForStart(List<Long> schools) {
        for (Long school : schools.stream().sorted().toList()) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('alliance-score:' || CAST(? AS text), 0))",
                    rs -> {}, school);
        }
    }

    public List<Route> routesForSubmission(long schoolId) {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(hashtextextended('alliance-score:' || CAST(? AS text), 0))",
                rs -> {}, schoolId);
        // Read after acquiring the lock so a start that just committed cannot be missed.
        return jdbc.query("""
                SELECT id, started_at, ended_at, score_state,
                       a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id
                FROM alliance_match
                WHERE status = 'IN_PROGRESS' AND deleted_at IS NULL AND score_state IS NOT NULL
                  AND ARRAY[a_university_1_id, a_university_2_id, b_university_1_id, b_university_2_id]
                      @> ARRAY[CAST(? AS bigint)]
                LIMIT 2
                """, (rs, _) -> new Route(rs.getLong("id"), rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class), "READY".equals(rs.getString("score_state")),
                List.of(rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))), schoolId);
    }
}
