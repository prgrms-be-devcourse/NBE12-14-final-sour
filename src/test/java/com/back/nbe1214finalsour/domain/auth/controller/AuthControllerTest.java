package com.back.nbe1214finalsour.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeResponse;
import com.back.nbe1214finalsour.domain.auth.dto.DepartmentSummary;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyResponse;
import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse;
import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse.LoginUser;
import com.back.nbe1214finalsour.domain.auth.dto.ReissueResponse;
import com.back.nbe1214finalsour.domain.auth.dto.SignupRequest;
import com.back.nbe1214finalsour.domain.auth.dto.SignupResponse;
import com.back.nbe1214finalsour.domain.auth.dto.UniversitySummary;
import com.back.nbe1214finalsour.domain.auth.service.EmailVerificationService;
import com.back.nbe1214finalsour.domain.auth.service.LoginService;
import com.back.nbe1214finalsour.domain.auth.service.LoginService.LoginResult;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.auth.service.TokenReissueService;
import com.back.nbe1214finalsour.domain.auth.service.TokenReissueService.ReissueResult;
import com.back.nbe1214finalsour.domain.auth.service.SignupService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.ApiAccessDeniedHandler;
import com.back.nbe1214finalsour.global.security.ApiAuthenticationEntryPoint;
import com.back.nbe1214finalsour.global.security.ApiResponseWriter;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.CorsConfig;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.SecurityConfig;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerTest {

    private static final UniversitySummary SNU = new UniversitySummary(12L, "서울대학교");

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @MockitoBean
    private SignupService signupService;

    @MockitoBean
    private LoginService loginService;

    @MockitoBean
    private TokenReissueService tokenReissueService;

    @MockitoBean
    private RefreshTokenService refreshTokenService;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("[POST] /api/v1/auth/email/code는 토큰 없이 학교와 유효 시간을 200으로 응답")
    void requestEmailCode() throws Exception {
        given(emailVerificationService.requestCode("student@snu.ac.kr")).willReturn(new EmailCodeResponse(SNU, 300));

        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@snu.ac.kr\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.university.id").value(12))
                .andExpect(jsonPath("$.data.university.name").value("서울대학교"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/email/verify는 가입 토큰과 학교, 유효 시간을 200으로 응답")
    void verifyEmailCode() throws Exception {
        given(emailVerificationService.verifyCode("student@snu.ac.kr", "482913")).willReturn(
                new EmailVerifyResponse("c1f0e7a2-4b1d-4a3e-9f1a-2d7c8e5b6a90", SNU, 1800));

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@snu.ac.kr\",\"code\":\"482913\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupToken").value("c1f0e7a2-4b1d-4a3e-9f1a-2d7c8e5b6a90"))
                .andExpect(jsonPath("$.data.university.id").value(12))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCodeRequests")
    @DisplayName("[POST] /api/v1/auth/email/code는 이메일 형식이 틀리면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void requestEmailCode_invalidInput(String body) throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("email"));

        verify(emailVerificationService, never()).requestCode(anyString());
    }

    static Stream<String> invalidCodeRequests() {
        return Stream.of("{}", "{\"email\":\"\"}", "{\"email\":\"not-an-email\"}");
    }

    @ParameterizedTest(name = "code={0}")
    @MethodSource("invalidCodes")
    @DisplayName("[POST] /api/v1/auth/email/verify는 코드가 숫자 6자리가 아니면 400 COMMON_INVALID_INPUT (실패 횟수에 넣지 않음)")
    void verifyEmailCode_invalidCodeFormat(String code) throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@snu.ac.kr\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value("code"));

        verify(emailVerificationService, never()).verifyCode(anyString(), anyString());
    }

    static Stream<String> invalidCodes() {
        return Stream.of("", "12345", "1234567", "12a456");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codeErrors")
    @DisplayName("[POST] /api/v1/auth/email/code의 도메인 오류는 명세서의 HTTP 상태와 코드로 응답")
    void requestEmailCode_businessErrors(ErrorCode errorCode, int status) throws Exception {
        given(emailVerificationService.requestCode(anyString())).willThrow(new BusinessException(errorCode));

        mockMvc.perform(post("/api/v1/auth/email/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@snu.ac.kr\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    static Stream<Arguments> codeErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.AUTH_UNSUPPORTED_DOMAIN, 400),
                Arguments.of(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED, 409),
                Arguments.of(ErrorCode.AUTH_CODE_COOLDOWN, 429));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verifyErrors")
    @DisplayName("[POST] /api/v1/auth/email/verify의 도메인 오류는 명세서의 HTTP 상태와 코드로 응답")
    void verifyEmailCode_businessErrors(ErrorCode errorCode, int status) throws Exception {
        given(emailVerificationService.verifyCode(anyString(), anyString())).willThrow(new BusinessException(errorCode));

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@snu.ac.kr\",\"code\":\"482913\"}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    static Stream<Arguments> verifyErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.AUTH_INVALID_CODE, 400),
                Arguments.of(ErrorCode.AUTH_CODE_EXPIRED, 410),
                Arguments.of(ErrorCode.AUTH_CODE_ATTEMPTS_EXCEEDED, 429));
    }

    // ===== 회원가입 =====

    private static final String TOKEN = "c1f0e7a2-4b1d-4a3e-9f1a-2d7c8e5b6a90";

    @Test
    @DisplayName("[POST] /api/v1/auth/signup은 토큰 없이 가입하고 회원·학교·학과를 201로 응답")
    void signup() throws Exception {
        given(signupService.signup(any())).willReturn(new SignupResponse(1024L, "대대대짱", SNU,
                new DepartmentSummary(301L, "컴퓨터공학부")));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("대대대짱", "Passw0rd!", 301)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(1024))
                .andExpect(jsonPath("$.data.nickname").value("대대대짱"))
                .andExpect(jsonPath("$.data.university.id").value(12))
                .andExpect(jsonPath("$.data.department.id").value(301))
                .andExpect(jsonPath("$.data.department.name").value("컴퓨터공학부"));

        verify(signupService).signup(new SignupRequest(TOKEN, "대대대짱", "Passw0rd!", 301L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignupRequests")
    @DisplayName("[POST] /api/v1/auth/signup은 닉네임·비밀번호·학과 규칙을 어기면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void signup_invalidInput(String reason, String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value(field));

        verify(signupService, never()).signup(any());
    }

    static Stream<Arguments> invalidSignupRequests() {
        return Stream.of(
                Arguments.of("닉네임 1자", signupBody("대", "Passw0rd!", 301), "nickname"),
                Arguments.of("닉네임 13자", signupBody("가".repeat(13), "Passw0rd!", 301), "nickname"),
                Arguments.of("닉네임 앞 공백", signupBody(" 대대대", "Passw0rd!", 301), "nickname"),
                Arguments.of("닉네임 없음",
                        "{\"signupToken\":\"" + TOKEN + "\",\"password\":\"Passw0rd!\",\"departmentId\":301}", "nickname"),
                Arguments.of("비밀번호 7자", signupBody("대대대짱", "Pass0r!", 301), "password"),
                Arguments.of("비밀번호 65자", signupBody("대대대짱", "Passw0rd!" + "a".repeat(56), 301), "password"),
                Arguments.of("비밀번호 숫자 없음", signupBody("대대대짱", "Password!", 301), "password"),
                Arguments.of("비밀번호 영문 없음", signupBody("대대대짱", "12345678!", 301), "password"),
                Arguments.of("비밀번호 특수문자 없음", signupBody("대대대짱", "Passw0rdd", 301), "password"),
                Arguments.of("비밀번호 공백 포함", signupBody("대대대짱", "Pass w0rd!", 301), "password"),
                Arguments.of("비밀번호 한글 포함", signupBody("대대대짱", "Passw0rd!한", 301), "password"),
                Arguments.of("학과 0", signupBody("대대대짱", "Passw0rd!", 0), "departmentId"),
                Arguments.of("가입 토큰 없음",
                        "{\"nickname\":\"대대대짱\",\"password\":\"Passw0rd!\",\"departmentId\":301}", "signupToken"));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/signup 검증 실패 로그에 비밀번호 원문이 남지 않는다")
    void signup_invalidPasswordIsNotLogged(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("대대대짱", "MySecretPw", 301)))
                .andExpect(status().isBadRequest());

        assertThat(output).contains("password").doesNotContain("MySecretPw");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signupErrors")
    @DisplayName("[POST] /api/v1/auth/signup의 도메인 오류는 명세서의 HTTP 상태와 코드로 응답")
    void signup_businessErrors(ErrorCode errorCode, int status) throws Exception {
        given(signupService.signup(any())).willThrow(new BusinessException(errorCode));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody("대대대짱", "Passw0rd!", 301)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    static Stream<Arguments> signupErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.AUTH_INVALID_SIGNUP_TOKEN, 400),
                Arguments.of(ErrorCode.USER_DEPARTMENT_MISMATCH, 400),
                Arguments.of(ErrorCode.USER_NICKNAME_DUPLICATED, 409),
                Arguments.of(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED, 409));
    }

    private static String signupBody(String nickname, String password, long departmentId) {
        return "{\"signupToken\":\"" + TOKEN + "\",\"nickname\":\"" + nickname + "\",\"password\":\"" + password
                + "\",\"departmentId\":" + departmentId + "}";
    }

    // ===== 로그인 =====

    @Test
    @DisplayName("[POST] /api/v1/auth/login은 access 토큰·회원을 200으로, refresh 토큰을 HttpOnly·Secure·SameSite=Strict 쿠키로 응답")
    void login() throws Exception {
        LoginResponse response = new LoginResponse("access.jwt.value", 3600,
                new LoginUser(1024L, "대대대짱", 12L, Role.USER));
        given(loginService.login("student@snu.ac.kr", "Passw0rd!", null))
                .willReturn(new LoginResult(response, "refresh-raw-value", Duration.ofDays(30)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("student@snu.ac.kr", "Passw0rd!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("access.jwt.value"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.data.user.id").value(1024))
                .andExpect(jsonPath("$.data.user.nickname").value("대대대짱"))
                .andExpect(jsonPath("$.data.user.universityId").value(12))
                .andExpect(jsonPath("$.data.user.role").value("USER"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", allOf(
                        startsWith("refreshToken=refresh-raw-value;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=2592000"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLoginRequests")
    @DisplayName("[POST] /api/v1/auth/login은 이메일·비밀번호가 비었거나 너무 길면 400 COMMON_INVALID_INPUT, 서비스를 부르지 않음")
    void login_invalidInput(String reason, String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data[0].field").value(field));

        verify(loginService, never()).login(anyString(), anyString(), any());
    }

    static Stream<Arguments> invalidLoginRequests() {
        return Stream.of(
                Arguments.of("이메일 없음", "{\"password\":\"Passw0rd!\"}", "email"),
                Arguments.of("이메일 빈 값", loginBody(" ", "Passw0rd!"), "email"),
                Arguments.of("이메일 255자", loginBody("a".repeat(243) + "@snu.ac.kr.x", "Passw0rd!"), "email"),
                Arguments.of("비밀번호 없음", "{\"email\":\"student@snu.ac.kr\"}", "password"),
                Arguments.of("비밀번호 65자", loginBody("student@snu.ac.kr", "a".repeat(65)), "password"));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/login 검증 실패 로그에 비밀번호 원문이 남지 않는다")
    void login_invalidPasswordIsNotLogged(CapturedOutput output) throws Exception {
        String secret = "MySecretPw" + "x".repeat(60);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("student@snu.ac.kr", secret)))
                .andExpect(status().isBadRequest());

        assertThat(output).contains("password").doesNotContain("MySecretPw");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loginErrors")
    @DisplayName("[POST] /api/v1/auth/login의 도메인 오류는 명세서의 HTTP 상태와 코드로 응답하고 쿠키를 보내지 않음")
    void login_businessErrors(ErrorCode errorCode, int status) throws Exception {
        given(loginService.login(anyString(), anyString(), any())).willThrow(new BusinessException(errorCode));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("student@snu.ac.kr", "Passw0rd!")))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    static Stream<Arguments> loginErrors() {
        return Stream.of(
                Arguments.of(ErrorCode.AUTH_INVALID_CREDENTIALS, 401),
                Arguments.of(ErrorCode.USER_SUSPENDED, 403),
                Arguments.of(ErrorCode.AUTH_LOGIN_LOCKED, 429));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/login은 요청에 실려 온 이전 refreshToken 쿠키를 서비스에 넘긴다 (SCRUM-72 A)")
    void login_passesPreviousCookie() throws Exception {
        given(loginService.login(anyString(), anyString(), any())).willReturn(new LoginResult(
                new LoginResponse("a", 3600, new LoginUser(1L, "n", 1L, Role.USER)), "new", Duration.ofDays(30)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("refreshToken", "old-refresh"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("student@snu.ac.kr", "Passw0rd!")))
                .andExpect(status().isOk());

        verify(loginService).login("student@snu.ac.kr", "Passw0rd!", "old-refresh");
    }

    // ===== 재발급·로그아웃 =====

    @Test
    @DisplayName("[POST] /api/v1/auth/reissue는 쿠키의 토큰으로 새 access 토큰을 200으로, 새 refresh 토큰을 쿠키로 응답")
    void reissue() throws Exception {
        given(tokenReissueService.reissue("old-refresh")).willReturn(
                new ReissueResult(new ReissueResponse("new.access", 3600), "new-refresh", Duration.ofDays(30)));

        mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.accessToken").value("new.access"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", allOf(
                        startsWith("refreshToken=new-refresh;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=2592000"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"))));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/reissue는 쿠키가 없으면 null을 넘기고, 서비스의 401 AUTH_INVALID_REFRESH_TOKEN을 쿠키 없이 응답")
    void reissue_withoutCookie() throws Exception {
        given(tokenReissueService.reissue(null))
                .willThrow(new BusinessException(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));

        mockMvc.perform(post("/api/v1/auth/reissue"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_REFRESH_TOKEN"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/logout은 로그인한 회원의 토큰을 지우고 204와 Max-Age=0 쿠키로 응답")
    void logout() throws Exception {
        String accessToken = jwtProvider.createAccessToken(new AuthUser(1024L, 12L, 34L, Role.USER), null);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refreshToken", "my-refresh")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", allOf(
                        startsWith("refreshToken=;"),
                        containsString("Path=/api/v1/auth"),
                        containsString("Max-Age=0"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"))));

        verify(refreshTokenService).revoke("my-refresh", 1024L);
    }

    @Test
    @DisplayName("[POST] /api/v1/auth/logout은 access 토큰이 없으면 401 COMMON_UNAUTHORIZED이고 토큰을 지우지 않음")
    void logout_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refreshToken", "my-refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verify(refreshTokenService, never()).revoke(any(), any());
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
