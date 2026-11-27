package com.back.nbe1214finalsour.domain.alliance.repository;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AllianceListReader {
    private static final String ACTIVE = "a.status <> 'EXPIRED' AND a.expired_at > :now";
    private static final String MEMBERSHIP = """
            SELECT 1 FROM alliance_university member
            WHERE member.alliance_id = a.id AND member.university_id = :school AND member.deleted_at IS NULL
            """;
    private static final String HISTORY = "EXISTS (" + MEMBERSHIP + " AND member.status IN ('ACCEPTED','LEFT') AND member.approver_user_id IS NOT NULL)";

    private final NamedParameterJdbcTemplate jdbc;

    public Page<Summary> directory(@Nullable Long school, Scope scope, @Nullable AllianceStatus status,
                                   boolean recruiting, OffsetDateTime now, @Nullable Long cursor, int size) {
        var args = page(cursor, size).addValue("now", now).addValue("school", school, Types.BIGINT)
                .addValue("status", name(status), Types.VARCHAR).addValue("recruiting", recruiting);
        // Limit after filtering; count only live accepted memberships, not applications or historical rows.
        var rows = jdbc.query("""
                SELECT id, name, effective_status, expired_at, member_count FROM (
                    SELECT a.id, a.name, a.expired_at,
                           CASE WHEN %s THEN a.status ELSE 'EXPIRED' END AS effective_status,
                           CASE WHEN %s THEN members.total ELSE 0 END AS member_count
                    FROM alliance a
                    CROSS JOIN LATERAL (
                        SELECT count(*) AS total FROM alliance_university au
                        WHERE au.alliance_id = a.id AND au.status = 'ACCEPTED' AND au.deleted_at IS NULL
                    ) members
                    WHERE a.deleted_at IS NULL AND (:cursor IS NULL OR a.id < :cursor) %s
                ) directory
                WHERE (:status IS NULL OR effective_status = :status)
                  AND (NOT :recruiting OR (effective_status = 'ACTIVE' AND member_count = 1))
                ORDER BY id DESC LIMIT :limit
                """.formatted(ACTIVE, ACTIVE, scope == Scope.MEMBERSHIP_HISTORY ? " AND " + HISTORY : ""), args,
                (rs, _) -> new Summary(rs.getLong("id"), rs.getString("name"),
                        AllianceStatus.valueOf(rs.getString("effective_status")), time(rs, "expired_at"), rs.getInt("member_count")));
        return Page.from(rows, size);
    }

    public List<School> schools(long allianceId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT u.id AS school_id, u.name AS school_name, u.logo_url
                FROM alliance a JOIN alliance_university au ON au.alliance_id = a.id
                JOIN university u ON u.id = au.university_id
                WHERE a.id = :alliance AND a.deleted_at IS NULL AND %s
                  AND au.status = 'ACCEPTED' AND au.deleted_at IS NULL
                ORDER BY au.id
                """.formatted(ACTIVE), new MapSqlParameterSource("alliance", allianceId).addValue("now", now),
                (rs, _) -> school(rs));
    }

    public boolean canRead(long allianceId, @Nullable Long school, OffsetDateTime now, boolean historical) {
        if (school == null) return false;
        var args = new MapSqlParameterSource("alliance", allianceId).addValue("school", school).addValue("now", now);
        String membership = historical ? HISTORY : ACTIVE + " AND EXISTS (" + MEMBERSHIP + " AND member.status = 'ACCEPTED')";
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM alliance a WHERE a.id = :alliance AND a.deleted_at IS NULL AND %s)
                """.formatted(membership), args, Boolean.class));
    }

    public Page<Application> applications(@Nullable Long allianceId, @Nullable Long school, @Nullable AllianceUniversityStatus status,
                                          @Nullable Long cursor, int size) {
        var args = page(cursor, size).addValue("alliance", allianceId, Types.BIGINT).addValue("school", school, Types.BIGINT)
                .addValue("status", name(status), Types.VARCHAR);
        // 가입 신청만 표시한다. 창립 및 연합 초대는 별도 경로다.
        var rows = jdbc.query("""
                SELECT au.id, au.status, au.created_at, a.id AS alliance_id, a.name AS alliance_name,
                       u.id AS school_id, u.name AS school_name, u.logo_url
                FROM alliance_university au JOIN alliance a ON a.id = au.alliance_id
                JOIN university u ON u.id = au.university_id
                WHERE a.deleted_at IS NULL AND au.deleted_at IS NULL
                  AND au.origin = 'APPLICATION'
                  AND %s
                  AND (:status IS NULL OR au.status = :status)
                  AND (:cursor IS NULL OR au.id < :cursor)
                ORDER BY au.id DESC LIMIT :limit
                """.formatted(allianceId == null ? "au.university_id = :school" : "a.id = :alliance"), args,
                (rs, _) -> new Application(rs.getLong("id"), new Name(rs.getLong("alliance_id"), rs.getString("alliance_name")),
                        school(rs), AllianceUniversityStatus.valueOf(rs.getString("status")), time(rs, "created_at")));
        return Page.from(rows, size);
    }

    public Page<Invitation> invitations(@Nullable Long allianceId, @Nullable Long school, @Nullable AllianceUniversityStatus status,
                                        OffsetDateTime now, @Nullable Long cursor, int size) {
        var args = page(cursor, size).addValue("alliance", allianceId, Types.BIGINT).addValue("school", school, Types.BIGINT)
                .addValue("status", name(status), Types.VARCHAR).addValue("now", now)
                .addValue("capacity", Alliance.MAX_SCHOOLS);
        var rows = jdbc.query("""
                SELECT au.id, au.status, au.created_at, a.id AS alliance_id, a.name AS alliance_name,
                       u.id AS school_id, u.name AS school_name, u.logo_url,
                       CASE WHEN au.status <> 'PENDING' THEN 'ALREADY_PROCESSED'
                            WHEN a.status <> 'ACTIVE' OR a.expired_at <= :now THEN 'ALLIANCE_NOT_ACTIVE'
                            WHEN (SELECT count(*) FROM alliance_university member
                                  WHERE member.alliance_id = a.id AND member.status = 'ACCEPTED'
                                    AND member.deleted_at IS NULL) >= :capacity THEN 'ALLIANCE_FULL'
                            WHEN EXISTS (SELECT 1 FROM alliance_university member
                                         WHERE member.university_id = au.university_id AND member.status = 'ACCEPTED'
                                           AND member.deleted_at IS NULL) THEN 'UNIVERSITY_ALREADY_JOINED'
                       END AS approval_block_reason
                FROM alliance_university au JOIN alliance a ON a.id = au.alliance_id
                JOIN university u ON u.id = au.university_id
                WHERE a.deleted_at IS NULL AND au.deleted_at IS NULL AND au.origin = 'INVITATION'
                  AND %s AND (:status IS NULL OR au.status = :status)
                  AND (:cursor IS NULL OR au.id < :cursor)
                ORDER BY au.id DESC LIMIT :limit
                """.formatted(allianceId == null ? "au.university_id = :school" : "a.id = :alliance"), args,
                (rs, _) -> {
                    String block = rs.getString("approval_block_reason");
                    return new Invitation(rs.getLong("id"), new Name(rs.getLong("alliance_id"), rs.getString("alliance_name")),
                            school(rs), AllianceUniversityStatus.valueOf(rs.getString("status")), time(rs, "created_at"),
                            block == null ? null : InvitationApprovalBlock.valueOf(block));
                });
        return Page.from(rows, size);
    }

    public Page<Match> matches(long allianceId, @Nullable Direction direction, @Nullable AllianceMatchStatus status,
                               @Nullable Long cursor, int size) {
        var args = page(cursor, size).addValue("alliance", allianceId)
                .addValue("direction", name(direction), Types.VARCHAR).addValue("status", name(status), Types.VARCHAR);
        var rows = jdbc.query("""
                SELECT m.*, m.alliance_a_id = :alliance AS outgoing,
                       opponent.id AS opponent_id, opponent.name AS opponent_name
                FROM alliance_match m JOIN alliance opponent ON opponent.id = CASE
                    WHEN m.alliance_a_id = :alliance THEN m.alliance_b_id ELSE m.alliance_a_id END
                WHERE (m.alliance_a_id = :alliance OR m.alliance_b_id = :alliance)
                  AND m.deleted_at IS NULL AND (:status IS NULL OR m.status = :status)
                  AND (:direction IS NULL OR (:direction = 'OUTGOING' AND m.alliance_a_id = :alliance)
                                         OR (:direction = 'INCOMING' AND m.alliance_b_id = :alliance))
                  AND (:cursor IS NULL OR m.id < :cursor)
                ORDER BY m.id DESC LIMIT :limit
                """, args, (rs, _) -> match(rs, allianceId));
        return Page.from(rows, size);
    }

    private static Match match(ResultSet rs, long allianceId) throws SQLException {
        boolean outgoing = rs.getBoolean("outgoing");
        var status = AllianceMatchStatus.valueOf(rs.getString("status"));
        boolean finished = status == AllianceMatchStatus.FINISHED;
        Long winner = rs.getObject("winner_alliance_id", Long.class);
        return new Match(rs.getLong("id"), new Name(rs.getLong("opponent_id"), rs.getString("opponent_name")),
                outgoing ? Direction.OUTGOING : Direction.INCOMING, status, rs.getInt("duration_hours"),
                time(rs, "request_expired_at"), time(rs, "scheduled_start_at"), time(rs, "started_at"), time(rs, "ended_at"),
                finished ? rs.getLong(outgoing ? "a_score" : "b_score") : null,
                finished ? rs.getLong(outgoing ? "b_score" : "a_score") : null,
                !finished ? null : winner == null ? Result.DRAW : winner == allianceId ? Result.WIN : Result.LOSE);
    }

    private static MapSqlParameterSource page(@Nullable Long cursor, int size) {
        return new MapSqlParameterSource().addValue("cursor", cursor, Types.BIGINT).addValue("limit", size + 1);
    }

    private static School school(ResultSet rs) throws SQLException {
        return new School(rs.getLong("school_id"), rs.getString("school_name"), rs.getString("logo_url"));
    }

    private static OffsetDateTime time(ResultSet rs, String field) throws SQLException {
        return rs.getObject(field, OffsetDateTime.class);
    }

    private static @Nullable String name(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
    }
}
