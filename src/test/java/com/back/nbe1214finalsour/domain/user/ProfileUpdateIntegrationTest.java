package com.back.nbe1214finalsour.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 프로필 수정 (추가 명세 §3-1, SCRUM-80)을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 *
 * <p>프로필 이미지는 개인 로고 변경권 구매로만 바꾼다 (SCRUM-105). 이 API는 닉네임만 바꾸고 {@code profileImageUrl}은 무시한다.
 * URL 규칙 사례는 {@code ProfileImageUrlPolicyTest}로 옮겼다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ProfileUpdateIntegrationTest {

    private static final String DOMAIN = "scrum80-test.ac.kr";
    private static final String NICKNAME = "프로필왕";
    private static final String CDN = "https://cdn.devcproject.cloud";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private JwtProvider jwtProvider;

    private long universityId;
    private long departmentId;
    private User user;
    private String accessToken;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('프로필테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        user = createUser(NICKNAME, "student@" + DOMAIN);
        accessToken = jwtProvider.createAccessToken(new AuthUser(user.getId(), universityId, departmentId, Role.USER), null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("닉네임만 보내면 닉네임만 바뀌고, 응답은 내 정보 형식이다")
    void updateNicknameOnly() throws Exception {
        String url = profileUrl(user.getId(), "before.png");
        jdbcTemplate.update("UPDATE users SET logo_url = ? WHERE id = ?", url, user.getId());

        update("{\"nickname\":\"새닉네임\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(user.getId()))
                .andExpect(jsonPath("$.data.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.data.email").value("student@" + DOMAIN))
                .andExpect(jsonPath("$.data.profileImageUrl").value(url))
                .andExpect(jsonPath("$.data.university.id").value(universityId))
                .andExpect(jsonPath("$.data.clicker.level").value(1));

        assertThat(row()).containsEntry("nickname", "새닉네임").containsEntry("logo_url", url);
    }

    @Test
    @DisplayName("profileImageUrl을 보내도 이미지는 바뀌지 않고 닉네임만 바뀐다 (이미지는 상점 구매로만 변경)")
    void profileImageUrlIsIgnored() throws Exception {
        String before = profileUrl(user.getId(), "before.png");
        jdbcTemplate.update("UPDATE users SET logo_url = ? WHERE id = ?", before, user.getId());
        String requested = profileUrl(user.getId(), "after.png");   // 예전 규칙으로는 허용되던 내 profile 경로

        update("{\"nickname\":\"둘다바꿈\",\"profileImageUrl\":\"" + requested + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("둘다바꿈"))
                .andExpect(jsonPath("$.data.profileImageUrl").value(before));

        assertThat(row()).containsEntry("nickname", "둘다바꿈").containsEntry("logo_url", before);
    }

    @Test
    @DisplayName("이미지가 없는 회원이 profileImageUrl만 보내도 이미지가 생기지 않는다")
    void profileImageUrlOnlyDoesNotSetImage() throws Exception {
        update("{\"profileImageUrl\":\"" + profileUrl(user.getId(), "new.png") + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()));

        assertThat(row()).containsEntry("nickname", NICKNAME).containsEntry("logo_url", null);
    }

    @Test
    @DisplayName("빈 본문이나 null 필드는 아무것도 바꾸지 않는다")
    void emptyOrNullFieldsKeepValues() throws Exception {
        String url = profileUrl(user.getId(), "keep.png");
        jdbcTemplate.update("UPDATE users SET logo_url = ? WHERE id = ?", url, user.getId());

        update("{}").andExpect(status().isOk());
        update("{\"nickname\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value(url));

        assertThat(row()).containsEntry("nickname", NICKNAME).containsEntry("logo_url", url);
    }

    @Test
    @DisplayName("다른 회원과 대소문자만 다른 닉네임이면 409 USER_NICKNAME_DUPLICATED이고 바뀌지 않는다")
    void duplicateNicknameIsRejected() throws Exception {
        createUser("TakenNick", "other@" + DOMAIN);

        update("{\"nickname\":\"takennick\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_DUPLICATED"));

        assertThat(row()).containsEntry("nickname", NICKNAME);
    }

    @Test
    @DisplayName("자기 닉네임과 같거나 대소문자만 바꾸는 것은 허용한다")
    void ownNicknameIsAllowed() throws Exception {
        jdbcTemplate.update("UPDATE users SET nickname = 'MyNick' WHERE id = ?", user.getId());

        update("{\"nickname\":\"MyNick\"}").andExpect(status().isOk());
        update("{\"nickname\":\"MYNICK\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("MYNICK"));
    }

    @Test
    @DisplayName("탈퇴한 회원이 쓰던 닉네임은 쓸 수 있다 (부분 UK)")
    void withdrawnUsersNicknameIsAvailable() throws Exception {
        User withdrawn = createUser("떠난회원", "gone@" + DOMAIN);
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?",
                withdrawn.getId());

        update("{\"nickname\":\"떠난회원\"}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("닉네임 규칙을 어기면 400 COMMON_INVALID_INPUT")
    void invalidNicknameIsRejected() throws Exception {
        update("{\"nickname\":\"대\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("탈퇴한 회원의 토큰이면 401 COMMON_UNAUTHORIZED, 토큰이 없어도 401")
    void unauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());
        update("{\"nickname\":\"새닉네임\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private User createUser(String nickname, String email) {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, nickname, email,
                emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
    }

    private ResultActions update(String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String profileUrl(Long userId, String fileName) {
        return CDN + "/profile/" + userId + "/" + fileName;
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("SELECT nickname, logo_url FROM users WHERE id = ?", user.getId());
    }
}
