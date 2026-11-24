package com.back.nbe1214finalsour.domain.user.devaccount;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.Role;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개발자 공용 계정 자동 등록 (SCRUM-237). V14의 와플대학교·운영팀에 계정을 만들고, 실제 로그인 API로 확인한다.
 *
 * <p>테스트 설정은 {@code dev-account.enabled=false}라 앱이 뜰 때는 만들지 않는다. 여기서는 설정을 직접 넣어 실행한다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class DevAccountInitializerIntegrationTest {

    private static final String EMAIL = "Dev-Account@Dev-Account-Test.example";
    private static final String NORMALIZED_EMAIL = "dev-account@dev-account-test.example";
    private static final String PASSWORD = "W4ffle!dev";
    private static final String NICKNAME = "테스트운영팀";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UniversityRepository universityRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private EmailHasher emailHasher;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private Validator validator;
    @Autowired
    private Clock clock;

    @AfterEach
    void tearDown() {
        List<Long> userIds = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE email_hash = ?", Long.class, emailHasher.hash(NORMALIZED_EMAIL));
        userIds.forEach(id -> redis.delete(RedisKey.AUTH_SESSION.of(id)));
        jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE email_hash = ?)",
                emailHasher.hash(NORMALIZED_EMAIL));
        jdbcTemplate.update("DELETE FROM users WHERE email_hash = ?", emailHasher.hash(NORMALIZED_EMAIL));
    }

    @Test
    @DisplayName("처음 실행하면 와플대학교 운영팀 소속 계정을 설정한 권한으로 만들고, 그 이메일·비밀번호로 로그인된다")
    void createsAccountThatCanLogIn() throws Exception {
        assertThat(initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN)).createIfAbsent()).isTrue();

        User user = userRepository.findByEmailHashAndDeletedAtIsNull(emailHasher.hash(NORMALIZED_EMAIL)).orElseThrow();
        long waffleId = universityRepository.findByName("와플대학교").orElseThrow().getId();
        assertThat(user.getUniversityId()).isEqualTo(waffleId);
        assertThat(user.getDepartmentId()).isEqualTo(
                departmentRepository.findByUniversity_IdAndName(waffleId, "운영팀").orElseThrow().getId());
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);

        login(PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.data.user.universityId").value(waffleId))
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
    }

    @Test
    @DisplayName("다시 실행하면 설정이 바뀌어도 기존 계정의 비밀번호·닉네임·권한을 바꾸지 않는다")
    void secondRunKeepsExistingAccount() throws Exception {
        initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN)).createIfAbsent();

        boolean created = initializer(properties(true, "Chang3d!pw", "바뀐운영팀", "와플대학교", Role.USER))
                .createIfAbsent();

        assertThat(created).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email_hash = ?", Long.class,
                emailHasher.hash(NORMALIZED_EMAIL))).isEqualTo(1L);
        login(PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.data.user.role").value("ADMIN"));
    }

    @Test
    @DisplayName("꺼져 있거나 이메일·비밀번호가 비어 있으면 계정을 만들지 않는다")
    void skipsWhenDisabledOrWithoutPassword() {
        assertThat(initializer(properties(false, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN)).createIfAbsent())
                .isFalse();
        assertThat(initializer(properties(true, null, NICKNAME, "와플대학교", Role.ADMIN)).createIfAbsent()).isFalse();
        assertThat(initializer(properties(true, "  ", NICKNAME, "와플대학교", Role.ADMIN)).createIfAbsent()).isFalse();
        assertThat(initializer(new DevAccountProperties(true, "", PASSWORD, NICKNAME, "와플대학교", "운영팀", Role.ADMIN))
                .createIfAbsent()).isFalse();

        assertThat(userRepository.existsByEmailHashAndDeletedAtIsNull(emailHasher.hash(NORMALIZED_EMAIL))).isFalse();
    }

    @Test
    @DisplayName("비밀번호가 가입 규칙에 맞지 않거나 학교가 없으면 설정 오류로 시작에 실패한다")
    void failsOnMisconfiguration() {
        assertThatThrownBy(() -> initializer(properties(true, "short", NICKNAME, "와플대학교", Role.ADMIN))
                .createIfAbsent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV_ACCOUNT_PASSWORD")
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> initializer(properties(true, PASSWORD, NICKNAME, "없는대학교", Role.ADMIN))
                .createIfAbsent())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없는대학교");

        assertThat(userRepository.existsByEmailHashAndDeletedAtIsNull(emailHasher.hash(NORMALIZED_EMAIL))).isFalse();
    }

    private DevAccountInitializer initializer(DevAccountProperties properties) {
        return new DevAccountInitializer(properties, userRepository, universityRepository, departmentRepository,
                emailHasher, passwordEncoder, validator, clock);
    }

    private static DevAccountProperties properties(boolean enabled, String password, String nickname,
            String universityName, Role role) {
        return new DevAccountProperties(enabled, EMAIL, password, nickname, universityName, "운영팀", role);
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + NORMALIZED_EMAIL + "\",\"password\":\"" + password + "\"}"));
    }
}
