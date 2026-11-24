package com.back.nbe1214finalsour.domain.alliance.dto;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;

public record AllianceApplicationResponse(
        Long id,
        Long allianceId,
        Long universityId,
        AllianceUniversityStatus status
) {
    public AllianceApplicationResponse(AllianceUniversity application) {
        this(
                application.getId(),
                application.getAllianceId(),
                application.getUniversityId(),
                application.getStatus()
        );
    }
}