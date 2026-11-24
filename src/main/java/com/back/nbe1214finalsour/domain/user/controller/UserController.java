package com.back.nbe1214finalsour.domain.user.controller;

import com.back.nbe1214finalsour.domain.auth.controller.RefreshTokenCookies;
import com.back.nbe1214finalsour.domain.user.controller.docs.UserErrorDocs;
import com.back.nbe1214finalsour.domain.user.dto.ChangePasswordRequest;
import com.back.nbe1214finalsour.domain.user.dto.MyInfoResponse;
import com.back.nbe1214finalsour.domain.user.dto.NicknameAvailabilityResponse;
import com.back.nbe1214finalsour.domain.user.dto.UpdateProfileRequest;
import com.back.nbe1214finalsour.domain.user.dto.WithdrawalRequest;
import com.back.nbe1214finalsour.domain.user.service.UserService;
import com.back.nbe1214finalsour.domain.user.validation.Nickname;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 정보 관리 API.
 *
 * <p>파라미터 제약이 있으므로 인터페이스로 분리하면 다른 메서드의 {@code @Valid}까지 HV000151이 발생한다.
 */
@Tag(name = "회원", description = "닉네임 확인과 회원 정보 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    /** 인증 불필요. 닉네임 규칙에 맞지 않으면 400 {@code COMMON_INVALID_INPUT} */
    @Operation(summary = "닉네임 사용 가능 여부 확인", description = "사용 중인 닉네임과 대소문자를 무시하고 비교한다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UserErrorDocs.INVALID_INPUT)
    })
    @GetMapping("/nickname/availability")
    public ApiResponse<NicknameAvailabilityResponse> checkNickname(@RequestParam @Nickname String nickname) {
        return ApiResponse.ok(new NicknameAvailabilityResponse(userService.isNicknameAvailable(nickname)));
    }

    /** 인증 필요 ({@code SecurityConfig}: 공개 목록에 없는 {@code /api/**}). 토큰이 없으면 401 {@code COMMON_UNAUTHORIZED} */
    @Operation(summary = "내 정보 조회", description = "현재 회원과 학교·학과·클리커 정보를 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = UserErrorDocs.UNAUTHORIZED)
    })
    @GetMapping("/me")
    public ApiResponse<MyInfoResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(userService.getMyInfo(authUser.userId()));
    }

    /**
     * 프로필 수정. 인증 필요. 닉네임을 바꾸고 내 정보와 같은 형식으로 응답한다.
     * 프로필 이미지는 상점의 개인 로고 변경권 구매로만 바꾼다. 닉네임 규칙 위반은 400 {@code COMMON_INVALID_INPUT}
     */
    @Operation(summary = "프로필 수정", description = "닉네임을 수정하고 바뀐 회원 정보를 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UserErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = UserErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`USER_NICKNAME_DUPLICATED` 이미 사용 중인 닉네임")
    })
    @PatchMapping("/me")
    public ApiResponse<MyInfoResponse> updateProfile(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody @Valid UpdateProfileRequest request) {
        return ApiResponse.ok(userService.updateProfile(authUser.userId(), request.nickname()));
    }

    /**
     * 비밀번호 변경. 인증 필요. 모든 refresh 토큰을 지우므로 이 브라우저의 refresh 쿠키도 지운다.
     * 응답은 로그아웃과 같이 본문 없는 204다 (계획 D2).
     */
    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호를 확인하고 변경한다. 모든 refresh 토큰과 refresh 쿠키를 지운다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UserErrorDocs.INVALID_INPUT + " / " + UserErrorDocs.INVALID_PASSWORD),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = UserErrorDocs.UNAUTHORIZED)
    })
    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody @Valid ChangePasswordRequest request) {
        userService.changePassword(authUser.userId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire().toString())
                .build();
    }

    /** 회원 탈퇴. 인증 필요. 비밀번호 변경과 같이 refresh 토큰을 모두 지우고 쿠키도 지운다. 응답은 본문 없는 204 */
    @Operation(summary = "회원 탈퇴", description = "비밀번호를 확인하고 탈퇴한다. 모든 refresh 토큰과 refresh 쿠키를 지운다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "탈퇴 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UserErrorDocs.INVALID_INPUT + " / " + UserErrorDocs.INVALID_PASSWORD),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = UserErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`USER_SUSPENDED` 정지 또는 차단된 회원")
    })
    @PostMapping("/me/withdrawal")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestBody @Valid WithdrawalRequest request) {
        userService.withdraw(authUser.userId(), request.password());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire().toString())
                .build();
    }
}
