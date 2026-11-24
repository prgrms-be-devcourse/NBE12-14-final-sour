package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceApplicationResponse;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceCreateRequest;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceResponse;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/alliances")
public class AllianceController {

    private final AllianceService allianceService;
    private final AllianceQueryService allianceQueryService;

    // 내 학교의 연합. 소속 연합이 없으면 data가 null이다
    @GetMapping("/me")
    public ApiResponse<AllianceResponse> getMyAlliance(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(allianceQueryService.findCurrentAlliance(authUser.universityId())
                .map(AllianceResponse::new)
                .orElse(null));
    }

    // 기존 연합 상세 조회
    @GetMapping("/{allianceId}")
    public ApiResponse<AllianceResponse> getAlliance(
            @PathVariable Long allianceId
    ) {
        return ApiResponse.ok(
                new AllianceResponse(
                        allianceQueryService.getAlliance(allianceId)
                )
        );
    }

    // 기존 연합 생성
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AllianceResponse> createAlliance(
            @AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody AllianceCreateRequest request
    ) {
        return ApiResponse.ok(
                new AllianceResponse(
                        allianceService.createAlliance(
                                authUser.userId(),
                                request.name(),
                                request.durationHours()
                        )
                )
        );
    }

    // 연합 가입 신청
    @PostMapping("/{allianceId}/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AllianceApplicationResponse> applyToAlliance(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long allianceId
    ) {
        return ApiResponse.ok(
                new AllianceApplicationResponse(
                        allianceService.applyToAlliance(
                                authUser.userId(),
                                allianceId
                        )
                )
        );
    }

    // 연합 가입 승인
    @PatchMapping("/applications/{applicationId}/approve")
    public ApiResponse<AllianceApplicationResponse> approveApplication(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long applicationId
    ) {
        return ApiResponse.ok(
                new AllianceApplicationResponse(
                        allianceService.approveApplication(
                                authUser.userId(),
                                applicationId
                        )
                )
        );
    }

    // 연합 가입 거절
    @PatchMapping("/applications/{applicationId}/reject")
    public ApiResponse<AllianceApplicationResponse> rejectApplication(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long applicationId
    ) {
        return ApiResponse.ok(
                new AllianceApplicationResponse(
                        allianceService.rejectApplication(
                                authUser.userId(),
                                applicationId
                        )
                )
        );
    }
}