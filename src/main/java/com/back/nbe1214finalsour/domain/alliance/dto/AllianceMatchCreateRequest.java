package com.back.nbe1214finalsour.domain.alliance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record AllianceMatchCreateRequest(
        @NotNull Long opponentAllianceId,
        @Min(1) @Max(24) int durationHours,
        OffsetDateTime scheduledStartAt
) {
    public AllianceMatchCreateRequest(Long opponentAllianceId, int durationHours) {
        this(opponentAllianceId, durationHours, null);
    }
}
