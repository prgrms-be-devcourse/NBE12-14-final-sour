package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.dto.AbuseRankingResponse;
import com.back.nbe1214finalsour.domain.click.service.AbuseRankingService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *
 * <p>API 문서를 다른 클릭 API처럼 {@code docs} 인터페이스로 빼지 않는다. 파라미터에 {@code @Min}·{@code @Max}가 있어서
 * 인터페이스를 구현하면 Hibernate Validator가 막는다 (HV000151, 구현 메서드에 제약을 새로 둘 수 없음).
 */
@Tag(name = "관리자", description = "ADMIN 권한이 필요한 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/abuse-rankings")
public class AdminAbuseController {

    private final AbuseRankingService abuseRankingService;

    /** 최근 3일 부정행위 순위. */
    @Operation(
            summary = "부정행위 순위 조회",
            description = """
                    최근 3일(오늘 포함, 한국 날짜) 동안 1분 클릭 한도(900)를 넘긴 횟수를 회원별로 합쳐 많은 순으로 보여 준다.
                    횟수가 같으면 userId 오름차순이다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_INPUT` page가 0 미만이거나 size가 1~100 밖"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` / `COMMON_INVALID_TOKEN` / `COMMON_EXPIRED_TOKEN`"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` ADMIN이 아님")
    })
    @GetMapping
    public ApiResponse<AbuseRankingResponse> getAbuseRanking(
            @Parameter(description = "페이지 번호 (0부터)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1~100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(abuseRankingService.getRanking(page, size));
    }
}
