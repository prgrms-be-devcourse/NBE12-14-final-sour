package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UserRankingResponse;
import com.back.nbe1214finalsour.domain.university.service.DepartmentRankingQueryService;
import com.back.nbe1214finalsour.domain.university.service.UniversityRankingQueryService;
import com.back.nbe1214finalsour.domain.university.service.UserRankingQueryService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rankings")
public class RankingController {

    private final UniversityRankingQueryService rankingQueryService;
    private final DepartmentRankingQueryService departmentRankingQueryService;
    private final UserRankingQueryService userRankingQueryService;

    @GetMapping("/universities")
    public ApiResponse<UniversityRankingResponse> getUniversities(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        Long myUniversityId = authUser != null
                ? authUser.universityId()
                : null;

        return ApiResponse.ok(
                rankingQueryService.getUniversities(
                        page,
                        size,
                        myUniversityId
                )
        );
    }

    @GetMapping("/universities/{universityId}/departments")
    public ApiResponse<DepartmentRankingResponse> getDepartmentRankings(
            @PathVariable Long universityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        Long myUniversityId = authUser != null
                ? authUser.universityId()
                : null;

        Long myDepartmentId = authUser != null
                ? authUser.departmentId()
                : null;

        return ApiResponse.ok(
                departmentRankingQueryService.getDepartments(
                        universityId,
                        page,
                        size,
                        myUniversityId,
                        myDepartmentId
                )
        );
    }

    @GetMapping("/universities/{universityId}/users")
    public ApiResponse<UserRankingResponse> getUserRankings(
            @PathVariable Long universityId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        Long myUniversityId = authUser != null
                ? authUser.universityId()
                : null;

        Long myUserId = authUser != null
                ? authUser.userId()
                : null;

        return ApiResponse.ok(
                userRankingQueryService.getUsers(
                        universityId,
                        page,
                        size,
                        myUniversityId,
                        myUserId
                )
        );
    }
}