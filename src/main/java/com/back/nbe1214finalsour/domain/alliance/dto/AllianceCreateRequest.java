package com.back.nbe1214finalsour.domain.alliance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AllianceCreateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull @Min(6) @Max(72) Integer durationHours
) {
}
