package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.dto.AbuseRankingResponse;
import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.click.service.AbuseRankingService;
import com.back.nbe1214finalsour.domain.click.service.ClickService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clicks")
public class ClickController {

    private final ClickService clickService;
    private final AbuseRankingService abuseRankingService;

    @PostMapping
    public ApiResponse<?> batchClick(@AuthenticationPrincipal AuthUser user,
                                     @RequestBody @Valid ClickRequest request) {
        Long userId = user.userId();
        Long universityId = user.universityId();
        Long departmentId = user.departmentId();

        ClickResponse response = clickService.click(userId, universityId, departmentId, request);

        return ApiResponse.ok(response);
    }

    /** 최근 3일 부정행위 순위 (관리자 전용). */
    @GetMapping("/abuse-rankings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AbuseRankingResponse> getAbuseRanking(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(abuseRankingService.getRanking(page, size));
    }
}
