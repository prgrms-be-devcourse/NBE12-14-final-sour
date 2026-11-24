package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityDetailResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.domain.university.service.UniversityQueryService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/universities")
public class UniversityController {

    private final UniversityQueryService universityQueryService;

    @GetMapping
    public ApiResponse<List<UniversitySearchResponse>> searchUniversities(
            @RequestParam @NotBlank String keyword,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(universityQueryService.searchUniversities(keyword, size));
    }

    @GetMapping("/{universityId}/departments")
    public ApiResponse<List<DepartmentResponse>> getDepartments(
            @PathVariable Long universityId
    ){
        return ApiResponse.ok(universityQueryService.getDepartments(universityId));
    }

    @GetMapping("/{universityId}")
    public ApiResponse<UniversityDetailResponse> getUniversityDetail(
            @PathVariable Long universityId,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        Long myUniversityId = authUser != null
                ? authUser.universityId()
                : null;

        return ApiResponse.ok(
                universityQueryService.getUniversityDetail(
                        universityId,
                        myUniversityId
                )
        );
    }
}
