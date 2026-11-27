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
        for (String email : List.of(NORMALIZED_EMAIL, demoEmail(1), demoEmail(2), demoEmail(3))) {
            String hash = emailHasher.hash(email);
            jdbcTemplate.queryForList("SELECT id FROM users WHERE email_hash = ?", Long.class, hash)
                    .forEach(id -> redis.delete(RedisKey.AUTH_SESSION.of(id)));
            jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM users WHERE email_hash = ?)", hash);
            jdbcTemplate.update("DELETE FROM users WHERE email_hash = ?", hash);
        }
        jdbcTemplate.update("DELETE FROM users WHERE nickname = ?", "선점닉네임");
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
        assertThat(initializer(new DevAccountProperties(true, "", PASSWORD, NICKNAME, "와플대학교", "운영팀", Role.ADMIN, 0))
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
        return properties(enabled, password, nickname, universityName, role, 0);
    }

    private static DevAccountProperties properties(boolean enabled, String password, String nickname,
            String universityName, Role role, int demoCount) {
        return new DevAccountProperties(enabled, EMAIL, password, nickname, universityName, "운영팀", role, demoCount);
    }

    /** dev-account@dev-account-test.example → dev-account{n}@dev-account-test.example */
    private static String demoEmail(int n) {
        return NORMALIZED_EMAIL.replace("@", n + "@");
    }

    private ResultActions login(String password) throws Exception {
        return login(NORMALIZED_EMAIL, password);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("시연용 계정을 개발자 이메일 @ 앞에 1~N 번호를 붙여 같은 비밀번호·학교·학과, USER 권한으로 만들고 로그인된다")
    void createsDemoAccountsThatCanLogIn() throws Exception {
        int created = initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN, 3)).createDemoAccountsIfAbsent();

        assertThat(created).isEqualTo(3);
        long waffleId = universityRepository.findByName("와플대학교").orElseThrow().getId();
        for (int n = 1; n <= 3; n++) {
            User user = userRepository.findByEmailHashAndDeletedAtIsNull(emailHasher.hash(demoEmail(n))).orElseThrow();
            assertThat(user.getUniversityId()).isEqualTo(waffleId);
            assertThat(user.getRole()).as("개발자 계정이 ADMIN이어도 시연용은 USER").isEqualTo(Role.USER);
            assertThat(user.getNickname()).isEqualTo(NICKNAME + n);
        }
        assertThat(userRepository.existsByEmailHashAndDeletedAtIsNull(emailHasher.hash(demoEmail(4)))).isFalse();
        login(demoEmail(2), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.nickname").value(NICKNAME + 2))
                .andExpect(jsonPath("$.data.user.role").value("USER"));
    }

    @Test
    @DisplayName("다시 실행하면 이미 있는 시연용 계정은 건너뛰고 없는 번호만 만든다")
    void demoAccountsSecondRunCreatesOnlyMissing() throws Exception {
        initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN, 2)).createDemoAccountsIfAbsent();

        int created = initializer(properties(true, "Chang3d!pw", NICKNAME, "와플대학교", Role.ADMIN, 3))
                .createDemoAccountsIfAbsent();

        assertThat(created).isEqualTo(1);
        login(demoEmail(1), PASSWORD).andExpect(status().isOk());
        login(demoEmail(3), "Chang3d!pw").andExpect(status().isOk());
    }

    @Test
    @DisplayName("꺼져 있거나 개수가 0이면 시연용 계정을 만들지 않고, 닉네임을 다른 회원이 쓰면 그 번호만 건너뛴다")
    void demoAccountsSkipWhenDisabledOrNicknameTaken() {
        assertThat(initializer(properties(false, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN, 3)).createDemoAccountsIfAbsent()).isZero();
        assertThat(initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN, 0)).createDemoAccountsIfAbsent()).isZero();
        assertThat(userRepository.existsByEmailHashAndDeletedAtIsNull(emailHasher.hash(demoEmail(1)))).isFalse();

        // 2번 닉네임을 다른 회원이 먼저 쓴다
        initializer(new DevAccountProperties(true, EMAIL, PASSWORD, "선점닉네임", "와플대학교", "운영팀", Role.USER, 0))
                .createIfAbsent();
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE email_hash = ?", NICKNAME + 2, emailHasher.hash(NORMALIZED_EMAIL));

        int created = initializer(properties(true, PASSWORD, NICKNAME, "와플대학교", Role.ADMIN, 3)).createDemoAccountsIfAbsent();

        assertThat(created).isEqualTo(2);
        assertThat(userRepository.existsByEmailHashAndDeletedAtIsNull(emailHasher.hash(demoEmail(2)))).isFalse();
    }
}
