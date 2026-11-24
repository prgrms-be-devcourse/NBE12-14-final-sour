package com.back.nbe1214finalsour.domain.support.controller;

import com.back.nbe1214finalsour.domain.support.controller.docs.SupportErrorDocs;
import com.back.nbe1214finalsour.domain.support.dto.AdminSupportDetailResponse;
import com.back.nbe1214finalsour.domain.support.dto.AdminSupportListResponse;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerRequest;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerResponse;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import com.back.nbe1214finalsour.domain.support.service.AdminSupportService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 문의 목록·상세·답변 (추가 명세 §3-9, 상세는 SCRUM-173).
 *
 * <p>권한은 {@code SecurityConfig}가 {@code /api/v1/admin/**} 경로로 한 번에 막는다.
 *
 * <p>페이지 파라미터에 제약이 있으므로 인터페이스로 분리하면 Hibernate Validator에서 HV000151이 발생한다.
 */
@Tag(name = "관리자", description = "ADMIN 권한이 필요한 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/supports")
public class AdminSupportController {

    private final AdminSupportService adminSupportService;

    /** 문의 목록. {@code status}를 생략하면 전체, {@code RECEIVED}·{@code ANSWERED}가 아니면 400 */
    @Operation(summary = "관리자 문의 목록 조회", description = "최신순으로 조회한다. status 생략 시 전체를 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT + " (status는 RECEIVED 또는 ANSWERED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = SupportErrorDocs.FORBIDDEN_ADMIN)
    })
    @GetMapping
    public ApiResponse<AdminSupportListResponse> getList(
            @RequestParam(required = false) @Nullable SupportStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(adminSupportService.getList(status, page, size));
    }

    /** 문의 상세. 본문·첨부·답변과 수신 이메일을 준다. 없으면 404 {@code SUPPORT_NOT_FOUND} */
    @Operation(summary = "관리자 문의 상세 조회", description = "문의 본문과 첨부·답변·수신 이메일을 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = SupportErrorDocs.FORBIDDEN_ADMIN),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = SupportErrorDocs.NOT_FOUND)
    })
    @GetMapping("/{supportId}")
    public ApiResponse<AdminSupportDetailResponse> getDetail(@PathVariable Long supportId) {
        return ApiResponse.ok(adminSupportService.getDetail(supportId));
    }

    /** 문의 답변. 답변 메일은 커밋 뒤 비동기로 보낸다 */
    @Operation(summary = "문의 답변", description = "접수된 문의에 답변한다. 답변 메일은 커밋 후 비동기로 발송한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "답변 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = SupportErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = SupportErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = SupportErrorDocs.FORBIDDEN_ADMIN),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = SupportErrorDocs.NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`SUPPORT_ALREADY_ANSWERED` 이미 답변한 문의")
    })
    @PostMapping("/{supportId}/answer")
    public ApiResponse<SupportAnswerResponse> answer(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long supportId,
            @Valid @RequestBody SupportAnswerRequest request) {
        return ApiResponse.ok(adminSupportService.answer(authUser.userId(), supportId, request));
    }
}
