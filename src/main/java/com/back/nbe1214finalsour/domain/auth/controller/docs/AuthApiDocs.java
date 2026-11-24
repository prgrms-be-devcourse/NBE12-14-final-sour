package com.back.nbe1214finalsour.domain.auth.controller.docs;

import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeRequest;
import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeResponse;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyRequest;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyResponse;
import com.back.nbe1214finalsour.domain.auth.dto.LoginRequest;
import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse;
import com.back.nbe1214finalsour.domain.auth.dto.ReissueResponse;
import com.back.nbe1214finalsour.domain.auth.dto.SignupRequest;
import com.back.nbe1214finalsour.domain.auth.dto.SignupResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

/** {@link com.back.nbe1214finalsour.domain.auth.controller.AuthController}의 API 문서. */
@Tag(name = "인증", description = "이메일 인증, 회원가입과 세션 관리")
public interface AuthApiDocs {
    @Operation(summary = "이메일 인증 코드 요청", description = "학교 이메일로 인증 코드를 발송한다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AuthErrorDocs.INVALID_INPUT + " / `AUTH_UNSUPPORTED_DOMAIN` 지원하지 않는 학교 도메인"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AuthErrorDocs.EMAIL_REGISTERED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`AUTH_CODE_COOLDOWN` 60초 안에 같은 이메일로 재요청하거나 하루 10번 초과 / " + AuthErrorDocs.IP_LIMIT)
    })
    ApiResponse<EmailCodeResponse> requestEmailCode(EmailCodeRequest request, HttpServletRequest servletRequest);

    @Operation(summary = "이메일 인증 코드 확인", description = "인증 코드를 확인하고 가입 토큰을 발급한다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AuthErrorDocs.INVALID_INPUT + " / `AUTH_INVALID_CODE` 인증 코드 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410",
                    description = "`AUTH_CODE_EXPIRED` 인증 코드 만료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`AUTH_CODE_ATTEMPTS_EXCEEDED` 인증 코드 시도 횟수 초과")
    })
    ApiResponse<EmailVerifyResponse> verifyEmailCode(EmailVerifyRequest request);

    @Operation(summary = "회원가입", description = "이메일 인증으로 발급받은 가입 토큰으로 회원가입한다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AuthErrorDocs.INVALID_INPUT + " / `AUTH_INVALID_SIGNUP_TOKEN` 가입 토큰이 유효하지 않음 / "
                            + "`USER_DEPARTMENT_MISMATCH` 학교와 학과 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AuthErrorDocs.EMAIL_REGISTERED + " / `USER_NICKNAME_DUPLICATED` 닉네임 중복")
    })
    ApiResponse<SignupResponse> signup(SignupRequest request);

    @Operation(summary = "로그인", description = "access 토큰은 본문으로 반환한다. 이전 refresh 토큰은 refreshToken 쿠키로 전달하며 "
            + "새 refresh 토큰을 같은 이름의 쿠키로 받는다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AuthErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`AUTH_INVALID_CREDENTIALS` 이메일 또는 비밀번호 불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "`USER_SUSPENDED` 이용이 제한된 계정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "`AUTH_LOGIN_LOCKED` 로그인 실패 횟수 초과 / " + AuthErrorDocs.IP_LIMIT)
    })
    ResponseEntity<ApiResponse<LoginResponse>> login(
            LoginRequest request, String previousRefreshToken, HttpServletRequest servletRequest);

    @Operation(summary = "토큰 재발급", description = "refreshToken 쿠키로 refresh 토큰을 전달하고 새 refresh 토큰을 같은 이름의 쿠키로 받는다. "
            + "access 토큰은 본문으로 반환한다.")
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`AUTH_INVALID_REFRESH_TOKEN` refresh 토큰 없음·만료·사용됨 / "
                            + "`AUTH_SESSION_REPLACED` 다른 기기에서 로그인함")
    })
    ResponseEntity<ApiResponse<ReissueResponse>> reissue(String refreshToken);

    @Operation(summary = "로그아웃", description = "현재 세션과 refresh 토큰을 삭제한다. refreshToken 쿠키가 없거나 이미 삭제됐어도 204를 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "로그아웃 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AuthErrorDocs.UNAUTHORIZED)
    })
    ResponseEntity<Void> logout(AuthUser authUser, String refreshToken);
}
