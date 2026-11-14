package com.back.nbe1214finalsour.domain.auth.controller;

import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeRequest;
import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeResponse;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyRequest;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyResponse;
import com.back.nbe1214finalsour.domain.auth.dto.LoginRequest;
import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse;
import com.back.nbe1214finalsour.domain.auth.dto.ReissueResponse;
import com.back.nbe1214finalsour.domain.auth.dto.SignupRequest;
import com.back.nbe1214finalsour.domain.auth.dto.SignupResponse;
import com.back.nbe1214finalsour.domain.auth.service.EmailVerificationService;
import com.back.nbe1214finalsour.domain.auth.service.LoginService;
import com.back.nbe1214finalsour.domain.auth.service.LoginService.LoginResult;
import com.back.nbe1214finalsour.domain.auth.service.LogoutService;
import com.back.nbe1214finalsour.domain.auth.service.TokenReissueService;
import com.back.nbe1214finalsour.domain.auth.service.TokenReissueService.ReissueResult;
import com.back.nbe1214finalsour.domain.auth.service.SignupService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증 API. 로그아웃만 인증이 필요하다 (API 명세서 §2) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final EmailVerificationService emailVerificationService;
    private final SignupService signupService;
    private final LoginService loginService;
    private final TokenReissueService tokenReissueService;
    private final LogoutService logoutService;

    @PostMapping("/email/code")
    public ApiResponse<EmailCodeResponse> requestEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        return ApiResponse.ok(emailVerificationService.requestCode(request.email()));
    }

    @PostMapping("/email/verify")
    public ApiResponse<EmailVerifyResponse> verifyEmailCode(@Valid @RequestBody EmailVerifyRequest request) {
        return ApiResponse.ok(emailVerificationService.verifyCode(request.email(), request.code()));
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(signupService.signup(request));
    }

    /** access 토큰은 본문으로, refresh 토큰은 {@code refreshToken} 쿠키로 보낸다. 이전 쿠키가 있으면 그 행은 지운다 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String previousRefreshToken) {
        LoginResult result = loginService.login(request.email(), request.password(), previousRefreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshTokenCookies.issue(result.refreshToken(), result.refreshTokenTtl()).toString())
                .body(ApiResponse.ok(result.response()));
    }

    /** 쿠키 없음·만료·이미 사용된 토큰은 401 {@code AUTH_INVALID_REFRESH_TOKEN} (서비스가 판단) */
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<ReissueResponse>> reissue(
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        ReissueResult result = tokenReissueService.reissue(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshTokenCookies.issue(result.refreshToken(), result.refreshTokenTtl()).toString())
                .body(ApiResponse.ok(result.response()));
    }

    /**
     * 이 회원의 refresh 토큰 행을 지우고 쿠키를 만료시킨다. 쿠키가 없거나 이미 지워졌어도 204다 (명세서 응답 그대로).
     * 인증은 {@code SecurityConfig}가 요구하므로 {@code authUser}는 {@code null}이 아니다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal AuthUser authUser,
            @CookieValue(name = RefreshTokenCookies.NAME, required = false) String refreshToken) {
        logoutService.logout(authUser.userId(), refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.expire().toString())
                .build();
    }
}
