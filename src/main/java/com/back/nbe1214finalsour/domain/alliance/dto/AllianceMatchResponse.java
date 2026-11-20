package com.back.nbe1214finalsour.domain.alliance.dto;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatch;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchScoreLedger.Score;
import java.time.OffsetDateTime;

public record AllianceMatchResponse(
        Long id, Long allianceAId, Long allianceBId, Long winnerAllianceId,
        AllianceMatchStatus status, Integer durationHours,
        OffsetDateTime requestExpiredAt, OffsetDateTime scheduledStartAt,
        OffsetDateTime startedAt, OffsetDateTime endedAt,
        Long aScore, Long bScore, Score score
) {
    public AllianceMatchResponse(AllianceMatch match) {
        this(match, null);
    }

    public AllianceMatchResponse(AllianceMatch match, Score score) {
        this(match.getId(), match.getAllianceAId(), match.getAllianceBId(),
                match.getWinnerAllianceId(), match.getStatus(), match.getDurationHours(),
                match.getRequestExpiredAt(), match.getScheduledStartAt(),
                match.getStartedAt(), match.getEndedAt(),
                match.getAScore(), match.getBScore(), score);
    }
}
