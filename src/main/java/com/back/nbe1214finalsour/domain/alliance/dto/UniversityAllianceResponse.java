package com.back.nbe1214finalsour.domain.alliance.dto;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;

public record UniversityAllianceResponse(long id, String name, AllianceStatus status, boolean joinable) {}
