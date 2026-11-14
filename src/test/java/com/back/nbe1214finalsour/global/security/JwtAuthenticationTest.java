package com.back.nbe1214finalsour.global.security;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

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
