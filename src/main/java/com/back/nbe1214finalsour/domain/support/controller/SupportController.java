package com.back.nbe1214finalsour.domain.support.controller;

import com.back.nbe1214finalsour.domain.support.controller.docs.SupportErrorDocs;
import com.back.nbe1214finalsour.domain.support.dto.SupportCreateRequest;
import com.back.nbe1214finalsour.domain.support.dto.SupportCreateResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportDetailResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportListResponse;
import com.back.nbe1214finalsour.domain.support.service.SupportService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.jwt.JwtAuthenticationFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 문의 API (추가 명세 §3-8).
 *
 * <p>페이지 파라미터에 제약이 있으므로 인터페이스로 분리하면 Hibernate Validator에서 HV000151이 발생한다.
 */
@Tag(name = "문의", description = "회원·비회원 문의 등록과 내 문의 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/supports")
public class SupportController {

    private final SupportService supportService;

    /**
     * 문의 등록. 인증 선택이지만 다른 "선택" API와 달리, 토큰을 보냈는데 틀렸으면 익명으로 넘기지 않고 401로 막는다 (계획 Q4).
     * 만료된 토큰을 가진 회원의 문의가 비회원 문의로 저장되어 "내 문의"에서 사라지지 않게 하고, 프론트가 재발급 후 다시 보내게
     * 한다. 비회원 한도는 {@code getRemoteAddr()}로 센다 (프록시 뒤 실제 IP는 {@code server.forward-headers-strategy}).
     */
    @Operation(summary = "문의 등록", description = """
            인증 선택: 토큰 없이 보내면 비회원 문의, 토큰을 보내면 회원 문의다. 보낸 토큰이 틀리면 401이다.
            비회원은 IP별, 회원은 회원별로 첫 등록부터 기본 1시간에 5건까지 등록할 수 있다 (설정 가능).
            비회원은 첨부할 수 없으며, 봇 확인이 켜져 있으면 기본 3번째 문의부터 확인이 필요하다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT + " / `SUPPORT_ATTACHMENT_NOT_ALLOWED` 비회원 첨부 / "
                            + "`FILE_INVALID_URL` 허용되지 않은 첨부 주소"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.TOKEN_ERROR),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`ABUSE_VERIFICATION_REQUIRED` 봇 확인 필요 / `ABUSE_VERIFICATION_FAILED` 봇 확인 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`COMMON_TOO_MANY_REQUESTS` 문의 등록 한도 초과")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupportCreateResponse> create(
            @AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody SupportCreateRequest request,
            HttpServletRequest servletRequest) {
        rejectInvalidToken(servletRequest);
        Long userId = authUser == null ? null : authUser.userId();
        return ApiResponse.ok(supportService.create(userId, servletRequest.getRemoteAddr(), request));
    }

    /** 내 문의 목록. 최신순, size 최대 100 */
    @Operation(summary = "내 문의 목록 조회", description = "내 문의를 최신순으로 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.UNAUTHORIZED)
    })
    @GetMapping("/me")
    public ApiResponse<SupportListResponse> getMine(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(supportService.getMine(authUser.userId(), page, size));
    }

    /** 내 문의 상세. 본인 문의만 볼 수 있다 */
    @Operation(summary = "내 문의 상세 조회", description = "본인이 등록한 문의의 상세와 답변을 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`SUPPORT_NOT_OWNER` 본인의 문의가 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = SupportErrorDocs.NOT_FOUND)
    })
    @GetMapping("/{supportId}")
    public ApiResponse<SupportDetailResponse> getDetail(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long supportId) {
        return ApiResponse.ok(supportService.getDetail(authUser.userId(), supportId));
    }

    /** JWT 필터가 남긴 토큰 오류(만료·위조·끊긴 세션)가 있으면 그 코드로 401 */
    private static void rejectInvalidToken(HttpServletRequest request) {
        if (request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE) instanceof ErrorCode tokenError) {
            throw new BusinessException(tokenError, "문의 등록에 유효하지 않은 토큰");
        }
    }
}
