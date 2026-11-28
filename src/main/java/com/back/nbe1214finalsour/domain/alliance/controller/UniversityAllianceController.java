package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.dto.UniversityAllianceResponse;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/universities")
public class UniversityAllianceController {
    private final AllianceQueryService alliances;

    @Operation(summary = "학교의 현재 연합 조회", description = "소속 연합이 없으면 data는 null이다. joinable은 조회 시점의 연합 상태와 정원을 나타낸다.")
    @GetMapping("/{universityId}/alliance")
    public ApiResponse<UniversityAllianceResponse> current(@PathVariable long universityId) {
        return ApiResponse.ok(alliances.getUniversityAlliance(universityId).orElse(null));
    }
}
