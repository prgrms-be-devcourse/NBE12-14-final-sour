package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.dto.AbuseRankingResponse;
import com.back.nbe1214finalsour.domain.click.service.AbuseRankingService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자용 부정행위 조회 (API 명세서 §4-9).
 *
 * <p>권한은 {@code SecurityConfig}가 {@code /api/v1/admin/**} 경로로 한 번에 막는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/abuse-rankings")
public class AdminAbuseController {

    private final AbuseRankingService abuseRankingService;

    /** 최근 3일 부정행위 순위. */
    @GetMapping
    public ApiResponse<AbuseRankingResponse> getAbuseRanking(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(abuseRankingService.getRanking(page, size));
    }
}
