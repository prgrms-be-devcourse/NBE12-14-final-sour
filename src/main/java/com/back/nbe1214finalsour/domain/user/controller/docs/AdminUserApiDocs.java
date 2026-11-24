package com.back.nbe1214finalsour.domain.user.controller.docs;

import com.back.nbe1214finalsour.domain.user.dto.AdminUserStatusRequest;
import com.back.nbe1214finalsour.domain.user.dto.AdminUserStatusResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.user.controller.AdminUserController}의 API 문서. */
@Tag(name = "관리자", description = "ADMIN 권한이 필요한 API")
public interface AdminUserApiDocs {
    @Operation(summary = "회원 상태 변경", description = "회원을 정지·차단하거나 해제한다. 정지·차단 시 refresh 토큰과 세션을 지운다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`COMMON_INVALID_INPUT` 상태 값 오류, 탈퇴 상태 요청 또는 관리자 자신의 상태 변경"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = UserErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`COMMON_FORBIDDEN` ADMIN 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`USER_NOT_FOUND` 없거나 탈퇴한 회원")
    })
    ApiResponse<AdminUserStatusResponse> changeStatus(AuthUser admin, Long userId, AdminUserStatusRequest request);
}
