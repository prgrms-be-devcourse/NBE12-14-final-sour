package com.back.nbe1214finalsour.domain.user.controller;

import com.back.nbe1214finalsour.domain.user.controller.docs.AdminUserApiDocs;
import com.back.nbe1214finalsour.domain.user.dto.AdminUserStatusRequest;
import com.back.nbe1214finalsour.domain.user.dto.AdminUserStatusResponse;
import com.back.nbe1214finalsour.domain.user.service.AdminUserService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 (추가 명세 §3-9).
 *
 * <p>권한은 {@code SecurityConfig}가 {@code /api/v1/admin/**} 경로로 한 번에 막는다 (익명 401, 일반 회원 403).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/users")
public class AdminUserController implements AdminUserApiDocs {

    private final AdminUserService adminUserService;

    /**
     * 계정 정지·차단·해제. 정지·차단하면 그 회원의 refresh 토큰과 로그인 세션을 지워 남은 토큰도 바로 401이 된다.
     * 없거나 탈퇴한 회원은 404 {@code USER_NOT_FOUND}, {@code WITHDRAWN}·자기 자신은 400 {@code COMMON_INVALID_INPUT}
     */
    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserStatusResponse> changeStatus(
            @AuthenticationPrincipal AuthUser admin,
            @PathVariable Long userId,
            @RequestBody @Valid AdminUserStatusRequest request) {
        return ApiResponse.ok(new AdminUserStatusResponse(userId,
                adminUserService.changeStatus(admin.userId(), userId, request.status())));
    }
}
