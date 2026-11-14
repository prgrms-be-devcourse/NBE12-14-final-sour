package com.back.nbe1214finalsour.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * URL 권한 규칙이 API 명세서 §2 표의 인증 표기와 같은지 확인한다 (회원 MVP 계획 §4-5, 단계 9).
 *
 * <p>{@link #SPEC}는 명세서 표 21행을 그대로 옮긴 것이다 (경로는 {@code /api/v1}, 경로 변수는 예시 값).
 * 명세서 표가 바뀌면 이 표와 {@link SecurityConfig}의 공개 목록을 함께 고친다.
 * 모든 {@code /api/v1/**} 요청은 테스트 컨트롤러가 받아 200과 로그인 회원 ID를 돌려주므로, 상태 코드는 보안 규칙만으로 정해진다.
 */
@WebMvcTest(controllers = UrlAuthorizationRulesTest.EchoController.class)
@Import({
        UrlAuthorizationRulesTest.EchoController.class,
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class UrlAuthorizationRulesTest {

    /** 명세서 §2 인증 열 */
    enum Auth {
        /** 불필요 */
        PUBLIC,
        /** 선택: 로그인 없이 호출, 로그인하면 내 정보 추가 */
        OPTIONAL,
        /** 쿠키: access 토큰 대신 refresh 쿠키 (재발급) */
        COOKIE,
        /** 필요 */
        REQUIRED
    }

    record Row(HttpMethod method, String path, Auth auth) {
        @Override
        public String toString() {
            return method + " " + path + " (" + auth + ")";
        }
    }

    static final List<Row> SPEC = List.of(
            new Row(HttpMethod.POST, "/api/v1/auth/email/code", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/auth/email/verify", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/auth/signup", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/auth/login", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/auth/reissue", Auth.COOKIE),
            new Row(HttpMethod.POST, "/api/v1/auth/logout", Auth.REQUIRED),
            new Row(HttpMethod.GET, "/api/v1/users/nickname/availability", Auth.PUBLIC),
            new Row(HttpMethod.GET, "/api/v1/users/me", Auth.REQUIRED),
            new Row(HttpMethod.POST, "/api/v1/clicks", Auth.REQUIRED),
            new Row(HttpMethod.GET, "/api/v1/clickers", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/clickers/upgrade", Auth.REQUIRED),
            new Row(HttpMethod.GET, "/api/v1/items", Auth.PUBLIC),
            new Row(HttpMethod.POST, "/api/v1/items/3/purchase", Auth.REQUIRED),
            new Row(HttpMethod.GET, "/api/v1/items/me/active", Auth.REQUIRED),
            new Row(HttpMethod.GET, "/api/v1/rankings/universities", Auth.OPTIONAL),
            new Row(HttpMethod.GET, "/api/v1/rankings/universities/12/departments", Auth.OPTIONAL),
            new Row(HttpMethod.GET, "/api/v1/rankings/universities/12/users", Auth.OPTIONAL),
            new Row(HttpMethod.GET, "/api/v1/universities", Auth.PUBLIC),
            new Row(HttpMethod.GET, "/api/v1/universities/12", Auth.OPTIONAL),
            new Row(HttpMethod.GET, "/api/v1/universities/12/departments", Auth.PUBLIC),
            new Row(HttpMethod.GET, "/api/v1/map/universities", Auth.PUBLIC));

    private static final AuthUser USER = new AuthUser(42L, 12L, 34L, Role.USER);
    private static final AuthUser ADMIN = new AuthUser(1L, 12L, 34L, Role.ADMIN);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JwtProperties jwtProperties;

    static Stream<Row> spec() {
        return SPEC.stream();
    }

    static Stream<Row> notRequired() {
        return SPEC.stream().filter(row -> row.auth() != Auth.REQUIRED);
    }

    static Stream<Row> required() {
        return SPEC.stream().filter(row -> row.auth() == Auth.REQUIRED);
    }

    @Test
    @DisplayName("표는 명세서 §2 MVP 21행이다")
    void specTableHas21Rows() {
        assertThat(SPEC).hasSize(21);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notRequired")
    @DisplayName("불필요·선택·쿠키 API는 access 토큰 없이 호출할 수 있다")
    void anonymousCanCallPublicApis(Row row) throws Exception {
        perform(row, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("required")
    @DisplayName("인증 필요 API는 토큰이 없으면 401 COMMON_UNAUTHORIZED")
    void anonymousGets401OnRequiredApis(Row row) throws Exception {
        perform(row, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spec")
    @DisplayName("유효한 토큰이면 모든 API를 호출할 수 있고 로그인 회원이 채워진다 (선택 API의 내 정보 포함)")
    void validTokenCanCallEveryApi(Row row) throws Exception {
        perform(row, jwtProvider.createAccessToken(USER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(42));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("required")
    @DisplayName("인증 필요 API에 만료된 토큰이면 401 COMMON_EXPIRED_TOKEN (프론트가 재발급할 신호)")
    void expiredTokenGets401OnRequiredApis(Row row) throws Exception {
        perform(row, expiredToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notRequired")
    @DisplayName("불필요·선택·쿠키 API는 만료된 토큰이 붙어 와도 익명으로 통과한다 (계획 Q4)")
    void expiredTokenIsAnonymousOnPublicApis(Row row) throws Exception {
        perform(row, expiredToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // ===== 표 밖의 경로 =====

    @Test
    @DisplayName("관리자 API: 익명 401, USER 403, ADMIN 200")
    void adminApis() throws Exception {
        Row admin = new Row(HttpMethod.GET, "/api/v1/admin/users", Auth.REQUIRED);
        perform(admin, null).andExpect(status().isUnauthorized());
        perform(admin, jwtProvider.createAccessToken(USER, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        perform(admin, jwtProvider.createAccessToken(ADMIN, null)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notInSpec")
    @DisplayName("명세서 표에 없는 /api 경로·메서드는 로그인이 필요하다 (공개는 목록에 넣을 때만)")
    void unlistedApisRequireLogin(Row row) throws Exception {
        perform(row, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }

    static Stream<Arguments> notInSpec() {
        return Stream.of(
                // 표에 없는 API (MVP 이후 기능)
                new Row(HttpMethod.GET, "/api/v1/alliances", Auth.REQUIRED),
                // 같은 경로라도 공개된 메서드만 공개
                new Row(HttpMethod.POST, "/api/v1/clickers", Auth.REQUIRED),
                new Row(HttpMethod.GET, "/api/v1/auth/login", Auth.REQUIRED),
                new Row(HttpMethod.DELETE, "/api/v1/universities/12", Auth.REQUIRED),
                // '*'는 경로 한 칸만
                new Row(HttpMethod.GET, "/api/v1/universities/12/members", Auth.REQUIRED),
                new Row(HttpMethod.GET, "/api/v1/rankings/universities/12/users/42", Auth.REQUIRED),
                // /api/v1 밖의 /api
                new Row(HttpMethod.GET, "/api/clickers", Auth.REQUIRED)
        ).map(Arguments::of);
    }

    private ResultActions perform(Row row, String accessToken) throws Exception {
        var builder = request(row.method(), row.path());
        if (accessToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return mockMvc.perform(builder);
    }

    private String expiredToken() {
        Instant past = Instant.now().minusSeconds(3600);
        return Jwts.builder()
                .subject("42").claim("universityId", 12L).claim("role", "USER")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /** 보안 규칙을 통과한 모든 {@code /api/**} 요청에 200과 로그인 회원 ID(익명이면 null)를 돌려준다 */
    @RestController
    static class EchoController {

        @RequestMapping("/api/**")
        ApiResponse<Long> echo(@AuthenticationPrincipal AuthUser authUser) {
            return ApiResponse.ok(authUser == null ? null : authUser.userId());
        }
    }
}
