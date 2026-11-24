package com.back.nbe1214finalsour.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.service.AdminUserService;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 계정 정지·해제 (추가 명세 §3-9, SCRUM-93)를 PostgreSQL·Redis(Testcontainers)로 확인한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AdminUserStatusIntegrationTest {

    private static final String DOMAIN = "scrum93-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private JwtProvider jwtProvider;

    private long universityId;
    private long departmentId;
    private User admin;
    private User user;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('정지테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        jdbcTemplate.update("INSERT INTO uni_domain (university_id, domain) VALUES (?, ?)", universityId, DOMAIN);
        admin = createUser("관리자", "admin@" + DOMAIN);
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        user = createUser("정지대상", EMAIL);
        clearRedis();
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE university_id = ?)",
                universityId);
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM uni_domain WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "BANNED"})
    @DisplayName("정지·차단: DB 상태 변경, refresh 토큰 모두 삭제, 커밋 뒤 로그인 세션 삭제·상태 캐시 갱신, 남은 access 토큰 401")
    void suspend(UserStatus target) throws Exception {
        String accessToken = loginAndGetAccessToken();
        assertThat(tokenHashesOf(user.getId())).hasSize(1);
        assertThat(redis.hasKey(RedisKey.AUTH_SESSION.of(user.getId()))).isTrue();

        UserStatus changed = adminUserService.changeStatus(admin.getId(), user.getId(), target);

        assertThat(changed).isEqualTo(target);
        assertThat(statusOf(user.getId())).isEqualTo(target.name());
        assertThat(tokenHashesOf(user.getId())).isEmpty();
        assertThat(redis.hasKey(RedisKey.AUTH_SESSION.of(user.getId()))).isFalse();
        assertThat(redis.opsForValue().get(RedisKey.USER_STATUS.of(user.getId()))).isEqualTo(target.name());
        me(accessToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_EXPIRED_TOKEN"));
        login().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
    }

    @Test
    @DisplayName("해제: ACTIVE로 돌아가고 상태 캐시도 ACTIVE, 다시 로그인할 수 있다")
    void reactivate() throws Exception {
        adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.SUSPENDED);

        UserStatus changed = adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.ACTIVE);

        assertThat(changed).isEqualTo(UserStatus.ACTIVE);
        assertThat(statusOf(user.getId())).isEqualTo("ACTIVE");
        assertThat(redis.opsForValue().get(RedisKey.USER_STATUS.of(user.getId()))).isEqualTo("ACTIVE");
        login().andExpect(status().isOk());
    }

    @Test
    @DisplayName("ACTIVE로 바꾸는 것은 로그인 상태를 끊지 않는다")
    void activatingKeepsSession() throws Exception {
        String accessToken = loginAndGetAccessToken();

        adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.ACTIVE);

        assertThat(tokenHashesOf(user.getId())).hasSize(1);
        me(accessToken).andExpect(status().isOk());
    }

    @Test
    @DisplayName("이미 같은 상태여도 같은 처리를 한다 (멱등)")
    void idempotent() {
        adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.BANNED);

        assertThat(adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.BANNED))
                .isEqualTo(UserStatus.BANNED);
        assertThat(statusOf(user.getId())).isEqualTo("BANNED");
    }

    @Test
    @DisplayName("없는 회원이면 404 USER_NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> adminUserService.changeStatus(admin.getId(), Long.MAX_VALUE, UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("탈퇴한 회원이면 404 USER_NOT_FOUND이고 상태는 그대로다")
    void withdrawnUserIsNotFound() {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());

        assertThatThrownBy(() -> adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.ACTIVE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        assertThat(statusOf(user.getId())).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("WITHDRAWN으로는 바꿀 수 없다: 400 COMMON_INVALID_INPUT")
    void cannotWithdraw() {
        assertThatThrownBy(() -> adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.WITHDRAWN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_INVALID_INPUT));
        assertThat(statusOf(user.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("관리자 자신의 상태는 바꿀 수 없다: 400 COMMON_INVALID_INPUT")
    void cannotChangeSelf() {
        assertThatThrownBy(() -> adminUserService.changeStatus(admin.getId(), admin.getId(), UserStatus.SUSPENDED))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_INVALID_INPUT));
        assertThat(statusOf(admin.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("다른 회원의 상태·refresh 토큰은 그대로다")
    void otherUserIsNotAffected() {
        insertToken(admin.getId(), "b".repeat(64));

        adminUserService.changeStatus(admin.getId(), user.getId(), UserStatus.SUSPENDED);

        assertThat(statusOf(admin.getId())).isEqualTo("ACTIVE");
        assertThat(tokenHashesOf(admin.getId())).containsExactly("b".repeat(64));
    }

    @Test
    @DisplayName("API 정지: 200 {userId, status}, 그 회원의 남은 access 토큰 401·재발급 401·로그인 403")
    void suspendViaApi() throws Exception {
        MockHttpServletResponse loginResponse = login().andExpect(status().isOk()).andReturn().getResponse();
        String accessToken = JsonPath.read(loginResponse.getContentAsString(), "$.data.accessToken");
        String refreshToken = loginResponse.getCookie("refreshToken").getValue();

        changeStatus(adminAccessToken(), user.getId(), "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(user.getId()))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        assertThat(statusOf(user.getId())).isEqualTo("SUSPENDED");
        me(accessToken).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/reissue").cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
        login().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
    }

    @Test
    @DisplayName("API 해제: 200 ACTIVE, 다시 로그인할 수 있다")
    void reactivateViaApi() throws Exception {
        changeStatus(adminAccessToken(), user.getId(), "{\"status\":\"BANNED\"}").andExpect(status().isOk());

        changeStatus(adminAccessToken(), user.getId(), "{\"status\":\"ACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        login().andExpect(status().isOk());
    }

    @Test
    @DisplayName("API 권한: 익명 401, 일반 회원 403이고 상태는 그대로다")
    void apiRequiresAdmin() throws Exception {
        mockMvc.perform(patch(statusUrl(user.getId())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        String userToken = jwtProvider.createAccessToken(
                new AuthUser(admin.getId(), universityId, departmentId, Role.USER), null);
        changeStatus(userToken, user.getId(), "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        assertThat(statusOf(user.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("API 없는 회원: 404 USER_NOT_FOUND")
    void apiNotFound() throws Exception {
        changeStatus(adminAccessToken(), Long.MAX_VALUE, "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"status\":\"WITHDRAWN\"}", "{\"status\":\"PAUSED\"}", "{\"status\":null}", "{}"})
    @DisplayName("API 잘못된 status: 400 COMMON_INVALID_INPUT이고 상태는 그대로다")
    void apiInvalidStatus(String body) throws Exception {
        changeStatus(adminAccessToken(), user.getId(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));

        assertThat(statusOf(user.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("API 자기 자신: 400 COMMON_INVALID_INPUT")
    void apiCannotChangeSelf() throws Exception {
        changeStatus(adminAccessToken(), admin.getId(), "{\"status\":\"SUSPENDED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    private String adminAccessToken() {
        return jwtProvider.createAccessToken(new AuthUser(admin.getId(), universityId, departmentId, Role.ADMIN), null);
    }

    private static String statusUrl(Long userId) {
        return "/api/v1/admin/users/" + userId + "/status";
    }

    private ResultActions changeStatus(String accessToken, Long userId, String body) throws Exception {
        return mockMvc.perform(patch(statusUrl(userId))
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private User createUser(String nickname, String email) {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), passwordEncoder.encode(PASSWORD), OffsetDateTime.now()));
    }

    private ResultActions login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private String loginAndGetAccessToken() throws Exception {
        String body = login().andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private ResultActions me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken));
    }

    private void insertToken(Long userId, String tokenHash) {
        jdbcTemplate.update("INSERT INTO refresh_token (user_id, token, expired_at) VALUES (?, ?, ?)",
                userId, tokenHash, OffsetDateTime.now().plusDays(1));
    }

    private List<String> tokenHashesOf(Long userId) {
        return jdbcTemplate.queryForList("SELECT token FROM refresh_token WHERE user_id = ?", String.class, userId);
    }

    private String statusOf(Long userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    /** 인증 키({@code auth:*})와 이 테스트 회원들의 상태 캐시를 지운다 */
    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.delete(List.of(RedisKey.USER_STATUS.of(admin.getId()), RedisKey.USER_STATUS.of(user.getId())));
    }
}
