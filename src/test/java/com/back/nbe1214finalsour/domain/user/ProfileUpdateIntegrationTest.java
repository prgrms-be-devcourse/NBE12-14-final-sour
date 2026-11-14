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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 프로필 수정 (추가 명세 §3-1, SCRUM-80)을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 *
 * <p>이미지 URL은 {@code file.public-base-url} 기본값 {@code https://cdn.devcproject.cloud} 기준이다 (명세서 예시와 같음).
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
    @DisplayName("이미지만 보내면 이미지만 바뀐다 (users.logo_url)")
    void updateImageOnly() throws Exception {
        String url = profileUrl(user.getId(), "3f9a7c2e-1b4d-4e8f-9a0b-1c2d3e4f5a6b.webp");

        update("{\"profileImageUrl\":\"" + url + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.data.profileImageUrl").value(url));

        assertThat(row()).containsEntry("nickname", NICKNAME).containsEntry("logo_url", url);
    }

    @Test
    @DisplayName("둘 다 보내면 둘 다 바뀐다")
    void updateBoth() throws Exception {
        String url = profileUrl(user.getId(), "me.jpg");

        update("{\"nickname\":\"둘다바꿈\",\"profileImageUrl\":\"" + url + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("둘다바꿈"))
                .andExpect(jsonPath("$.data.profileImageUrl").value(url));
    }

    @Test
    @DisplayName("빈 본문이나 null 필드는 아무것도 바꾸지 않는다 (이미지 삭제 미지원, 계획 Q3)")
    void emptyOrNullFieldsKeepValues() throws Exception {
        String url = profileUrl(user.getId(), "keep.png");
        jdbcTemplate.update("UPDATE users SET logo_url = ? WHERE id = ?", url, user.getId());

        update("{}").andExpect(status().isOk());
        update("{\"nickname\":null,\"profileImageUrl\":null}")
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://cdn.devcproject.cloud/profile/999999/a.png",         // 다른 회원 경로
            "https://evil.example.com/profile/{id}/a.png",                // 우리 CDN이 아님
            "http://cdn.devcproject.cloud/profile/{id}/a.png",            // https가 아님
            "https://cdn.devcproject.cloud.evil.com/profile/{id}/a.png",  // 접두어만 같은 다른 호스트
            "https://cdn.devcproject.cloud/custom-logo/{id}/a.png",       // 다른 용도
            "https://cdn.devcproject.cloud/profile/{id}/../999999/a.png", // 경로 이동
            "https://cdn.devcproject.cloud/profile/{id}/sub/a.png",       // 하위 경로
            "https://cdn.devcproject.cloud/profile/{id}/a.png?x=1",       // 쿼리
            "https://cdn.devcproject.cloud/profile/{id}/a.gif",           // PROFILE이 허용하지 않는 형식
            "https://cdn.devcproject.cloud/profile/{id}/",                // 파일 이름 없음
            ""})
    @DisplayName("우리 CDN의 내 profile 경로가 아니면 400 FILE_INVALID_URL이고 함께 보낸 닉네임도 바뀌지 않는다")
    void invalidImageUrlIsRejected(String template) throws Exception {
        String url = template.replace("{id}", user.getId().toString());

        update("{\"nickname\":\"바뀌면안됨\",\"profileImageUrl\":\"" + url + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_INVALID_URL"));

        assertThat(row()).containsEntry("nickname", NICKNAME).containsEntry("logo_url", null);
    }

    @Test
    @DisplayName("users.logo_url(500자)보다 긴 URL은 400 FILE_INVALID_URL")
    void tooLongUrlIsRejected() throws Exception {
        String url = profileUrl(user.getId(), "a".repeat(500) + ".png");

        update("{\"profileImageUrl\":\"" + url + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_INVALID_URL"));
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
