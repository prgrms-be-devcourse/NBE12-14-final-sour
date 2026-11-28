package com.back.nbe1214finalsour.domain.alliance.dto;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

public final class AllianceLists {
    public static final int MAX_SIZE = 50;
    public static final String DEFAULT_SIZE = "20";

    private AllianceLists() {}

    public enum Scope { DIRECTORY, MEMBERSHIP_HISTORY }
    public enum Direction { INCOMING, OUTGOING }
    public enum Result { WIN, LOSE, DRAW }
    public enum InvitationApprovalBlock { ALREADY_PROCESSED, ALLIANCE_NOT_ACTIVE, ALLIANCE_FULL, UNIVERSITY_ALREADY_JOINED }

    public interface Row { long id(); }

    public record Page<T extends Row>(List<T> items, @Nullable Long nextCursor) {
        public static <T extends Row> Page<T> from(List<T> rows, int size) {
            boolean more = rows.size() > size;
            return new Page<>(List.copyOf(rows.subList(0, Math.min(size, rows.size()))),
                    more ? rows.get(size - 1).id() : null);
        }
    }

    public record Name(long id, String name) {}
    public record Summary(long id, String name, AllianceStatus status, OffsetDateTime expiredAt, int memberCount) implements Row {}
    public record School(long id, String name, @Nullable String logoUrl) {}
    public record Application(long id, Name alliance, School university, AllianceUniversityStatus status,
                              OffsetDateTime createdAt) implements Row {}
    public record Invitation(long id, Name alliance, School university, AllianceUniversityStatus status,
                             OffsetDateTime createdAt, @Nullable InvitationApprovalBlock approvalBlockReason) implements Row {}
    public record Match(long id, Name opponent, Direction direction, AllianceMatchStatus status,
                        int durationHours, OffsetDateTime requestExpiredAt, @Nullable OffsetDateTime scheduledStartAt,
                        @Nullable OffsetDateTime startedAt, @Nullable OffsetDateTime endedAt,
                        @Nullable Long myScore, @Nullable Long opponentScore, @Nullable Result result) implements Row {}
}
