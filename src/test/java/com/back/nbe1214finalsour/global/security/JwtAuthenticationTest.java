package com.back.nbe1214finalsour.global.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bearer 토큰 → {@link AuthUser} 주입과, 토큰 오류 사유에 따른 401 코드를 확인한다 (SCRUM-62).
 * URL 규칙은 아직 {@code /api/**} 임시 허용이므로 "보호 경로"는 {@code @PreAuthorize}로 만든다.
 */
@WebMvcTest(controllers = JwtAuthenticationTest.TestController.class)
@Import({
        JwtAuthenticationTest.TestController.class,
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class JwtAuthenticationTest {

    /** 명세서 인증 "선택" 경로 (학교 상세). 공개 목록은 SecurityConfig.PUBLIC_GET */
    private static final String OPTIONAL_PATH = "/api/v1/universities/7";

    private static final AuthUser USER = new AuthUser(42L, 7L, 34L, Role.USER);
    private static final AuthUser ADMIN = new AuthUser(1L, 7L, 34L, Role.ADMIN);
    private static final String SESSION_ID = "6f1c2b9e-3d4a-4e5f-8a7b-0c1d2e3f4a5b";
    private static final String OTHER_SESSION_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    private ResultActions getWithToken(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String expiredToken() {
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject("42").claim("universityId", 7L).claim("role", "USER")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private String forgedToken() {
        return Jwts.builder()
                .subject("42").claim("universityId", 7L).claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor("another-secret-key-that-is-long-enough-000".getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Nested
    @DisplayName("유효한 토큰")
    class ValidToken {

        @Test
        @DisplayName("@AuthenticationPrincipal AuthUser에 claim 값이 들어간다")
        void injectsAuthUser() throws Exception {
            getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(42))
                    .andExpect(jsonPath("$.data.universityId").value(7))
                    .andExpect(jsonPath("$.data.role").value("USER"));
        }

        @Test
        @DisplayName("role이 ADMIN이면 hasRole('ADMIN')을 통과한다")
        void adminRole() throws Exception {
            getWithToken("/api/test/jwt/admin", jwtProvider.createAccessToken(ADMIN, null))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("role이 USER면 관리자 API에서 403 COMMON_FORBIDDEN")
        void userRoleForbidden() throws Exception {
            getWithToken("/api/test/jwt/admin", jwtProvider.createAccessToken(USER, null))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("보호 경로의 401 사유")
    class Unauthorized {

        @Test
        @DisplayName("토큰이 없으면 COMMON_UNAUTHORIZED")
        void noToken() throws Exception {
            mockMvc.perform(get("/api/test/jwt/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }

        @Test
        @DisplayName("만료된 토큰이면 COMMON_EXPIRED_TOKEN")
        void expired() throws Exception {
            getWithToken("/api/test/jwt/me", expiredToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"))
                    .andExpect(jsonPath("$.message").value("토큰이 만료되었습니다."));
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰이면 COMMON_INVALID_TOKEN")
        void forged() throws Exception {
            getWithToken("/api/test/jwt/me", forgedToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_TOKEN"));
        }

        @Test
        @DisplayName("JWT 형식이 아니면 COMMON_INVALID_TOKEN")
        void malformed() throws Exception {
            getWithToken("/api/test/jwt/me", "garbage")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_TOKEN"));
        }

        @Test
        @DisplayName("Bearer가 아닌 Authorization은 무시하고 COMMON_UNAUTHORIZED")
        void otherScheme() throws Exception {
            mockMvc.perform(get("/api/test/jwt/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("인증 선택 경로 (계획 Q4: 토큰 오류는 익명으로 처리)")
    class OptionalAuth {

        @Test
        @DisplayName("토큰이 없으면 AuthUser는 null")
        void anonymous() throws Exception {
            mockMvc.perform(get(OPTIONAL_PATH))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("만료된 토큰이어도 401이 아니라 익명으로 통과한다")
        void expiredIsAnonymous() throws Exception {
            getWithToken(OPTIONAL_PATH, expiredToken())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("유효한 토큰이면 AuthUser가 채워진다")
        void valid() throws Exception {
            getWithToken(OPTIONAL_PATH, jwtProvider.createAccessToken(USER, null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(42));
        }
    }

    @Nested
    @DisplayName("세션 확인 (계정당 1기기, SCRUM-86)")
    class SessionCheck {

        @Test
        @DisplayName("sid가 Redis 현재 세션과 같으면 통과한다")
        void currentSession() throws Exception {
            given(loginSessionStore.find(42L)).willReturn(Optional.of(SESSION_ID));

            getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, SESSION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(42));
        }

        @Test
        @DisplayName("sid가 Redis 현재 세션과 다르면(다른 기기 로그인) 401 AUTH_SESSION_REPLACED")
        void replacedSession() throws Exception {
            given(loginSessionStore.find(42L)).willReturn(Optional.of(OTHER_SESSION_ID));

            getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, SESSION_ID))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_SESSION_REPLACED"))
                    .andExpect(jsonPath("$.message").value("다른 기기에서 로그인해 로그아웃되었습니다. 다시 로그인해주세요."));
        }

        @Test
        @DisplayName("Redis에 현재 세션이 없으면(유실·로그아웃) 401 COMMON_EXPIRED_TOKEN: 프론트가 재발급을 시도하게 한다")
        void missingSession() throws Exception {
            given(loginSessionStore.find(42L)).willReturn(Optional.empty());

            getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, SESSION_ID))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));
        }

        @Test
        @DisplayName("sid가 없는 토큰(SCRUM-84 배포 전 발급)은 Redis를 보지 않고 통과한다 (계획 Q3)")
        void tokenWithoutSessionSkipsLookup() throws Exception {
            getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, null))
                    .andExpect(status().isOk());

            verify(loginSessionStore, never()).find(any());
        }

        @Test
        @DisplayName("인증 선택 경로에서는 끊긴 세션도 401이 아니라 익명으로 통과한다")
        void replacedSessionIsAnonymousOnOptionalPath() throws Exception {
            given(loginSessionStore.find(42L)).willReturn(Optional.of(OTHER_SESSION_ID));

            getWithToken(OPTIONAL_PATH, jwtProvider.createAccessToken(USER, SESSION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Redis 장애는 삼키지 않는다: 인증하지 않고 요청이 실패한다 (계획 Q4)")
        void redisFailureIsNotSwallowed() {
            given(loginSessionStore.find(42L)).willThrow(new RedisConnectionFailureException("down"));

            assertThatThrownBy(() -> getWithToken("/api/test/jwt/me", jwtProvider.createAccessToken(USER, SESSION_ID)))
                    .isInstanceOf(RedisConnectionFailureException.class);
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/api/test/jwt/me")
        @PreAuthorize("isAuthenticated()")
        ApiResponse<AuthUser> me(@AuthenticationPrincipal AuthUser authUser) {
            return ApiResponse.ok(authUser);
        }

        @GetMapping("/api/test/jwt/admin")
        @PreAuthorize("hasRole('ADMIN')")
        ApiResponse<Void> admin() {
            return ApiResponse.ok();
        }

        @GetMapping(OPTIONAL_PATH)
        ApiResponse<AuthUser> optional(@AuthenticationPrincipal AuthUser authUser) {
            return ApiResponse.ok(authUser);
        }
    }
}
