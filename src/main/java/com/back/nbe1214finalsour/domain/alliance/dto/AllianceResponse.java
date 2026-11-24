package com.back.nbe1214finalsour.domain.alliance.dto;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import java.time.OffsetDateTime;

public record AllianceResponse(
        Long id,
        String name,
        Long totalScore,
        AllianceStatus status,
        Integer durationHours,
        OffsetDateTime expiredAt
) {
    public AllianceResponse(Alliance alliance) {
        this(
                alliance.getId(),
                alliance.getName(),
                alliance.getTotalScore(),
                alliance.getStatus(),
                alliance.getDurationHours(),
                alliance.getExpiredAt()
        );
    }
}
