package com.back.nbe1214finalsour.domain.auth;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.dto.SignupRequest;
import com.back.nbe1214finalsour.domain.auth.mail.VerificationMailSender;
import com.back.nbe1214finalsour.domain.auth.service.SignupService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.jayway.jsonpath.JsonPath;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 인증 → 회원가입, 닉네임 확인을 PostgreSQL·Redis(Testcontainers)와 실제 HTTP 처리로 확인한다.
 *
 * <p>가입 토큰은 커밋 뒤에 지우므로 테스트 트랜잭션으로 롤백하지 않는다. 넣은 행과 Redis {@code auth:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SignupIntegrationTest {

    private static final String DOMAIN = "scrum65-test.ac.kr";
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
    private SignupService signupService;

    @Autowired
    private VerificationMailSender mailSender;

    private long universityId;
    private long departmentId;
    private long otherUniversityId;
    private long otherDepartmentId;

    @BeforeEach
    void setUp() {
        clearRedis();
        universityId = insert("INSERT INTO university (name) VALUES ('가입테스트대') RETURNING id");
        departmentId = insert("INSERT INTO department (university_id, name) VALUES (" + universityId
                + ", '컴퓨터공학과') RETURNING id");
        otherUniversityId = insert("INSERT INTO university (name) VALUES ('가입테스트대B') RETURNING id");
        otherDepartmentId = insert("INSERT INTO department (university_id, name) VALUES (" + otherUniversityId
                + ", '경영학과') RETURNING id");
        jdbcTemplate.update("INSERT INTO uni_domain (university_id, domain) VALUES (?, ?)", universityId, DOMAIN);
    }

    @AfterEach
    void tearDown() {
        clearRedis();
        jdbcTemplate.update("DELETE FROM users WHERE university_id IN (?, ?)", universityId, otherUniversityId);
        jdbcTemplate.update("DELETE FROM uni_domain WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id IN (?, ?)", universityId, otherUniversityId);
        jdbcTemplate.update("DELETE FROM university WHERE id IN (?, ?)", universityId, otherUniversityId);
    }

    // ===== 회원가입 =====

    @Test
    @DisplayName("코드 요청 → 확인 → 가입: 201로 회원·학교·학과를 응답하고, 비밀번호는 BCrypt, 이메일은 암호화해 저장하며 토큰을 지운다")
    void signupAfterEmailVerification() throws Exception {
        String token = verifyEmail("Student@SCRUM65-Test.ac.kr");

        String body = signup(token, "가입왕", departmentId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("가입왕"))
                .andExpect(jsonPath("$.data.university.id").value(universityId))
                .andExpect(jsonPath("$.data.university.name").value("가입테스트대"))
                .andExpect(jsonPath("$.data.department.id").value(departmentId))
                .andExpect(jsonPath("$.data.department.name").value("컴퓨터공학과"))
                .andReturn().getResponse().getContentAsString();

        long userId = ((Number) JsonPath.read(body, "$.data.userId")).longValue();
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(EMAIL);
        assertThat(user.getPassword()).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isTrue();
        assertThat(user.getUniversityId()).isEqualTo(universityId);
        assertThat(user.getDepartmentId()).isEqualTo(departmentId);
        assertThat(user.getApprovedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId))
                .doesNotContain("student");
        assertThat(redis.hasKey("auth:signup:" + token)).isFalse();
    }

    @Test
    @DisplayName("가입: 없거나 만료된 토큰이면 400 AUTH_INVALID_SIGNUP_TOKEN")
    void invalidSignupToken() throws Exception {
        signup(UUID.randomUUID().toString(), "가입왕", departmentId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_SIGNUP_TOKEN"));
    }

    @Test
    @DisplayName("가입: 발급한 형식(UUID)이 아닌 토큰도 400 AUTH_INVALID_SIGNUP_TOKEN (':'가 섞여 키를 만들 수 없는 값 포함)")
    void malformedSignupToken() throws Exception {
        for (String token : new String[]{"a:b", "auth:signup:x", "not-a-uuid"}) {
            signup(token, "가입왕", departmentId)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_SIGNUP_TOKEN"));
        }
    }

    @Test
    @DisplayName("가입: 다른 학교의 학과면 400 USER_DEPARTMENT_MISMATCH이고, 토큰은 남아 다시 가입할 수 있다")
    void departmentOfOtherUniversity() throws Exception {
        String token = signupToken(EMAIL);

        signup(token, "가입왕", otherDepartmentId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_DEPARTMENT_MISMATCH"));

        assertThat(redis.hasKey("auth:signup:" + token)).isTrue();
        signup(token, "가입왕", departmentId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("가입: 없는 학과면 400 USER_DEPARTMENT_MISMATCH")
    void unknownDepartment() throws Exception {
        signup(signupToken(EMAIL), "가입왕", Long.MAX_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_DEPARTMENT_MISMATCH"));
    }

    @Test
    @DisplayName("가입: 대소문자만 다른 닉네임이 있으면 409 USER_NICKNAME_DUPLICATED이고, 토큰은 남는다")
    void nicknameDuplicatedIgnoringCase() throws Exception {
        insertUser("TakenNick", "a".repeat(64), false);
        String token = signupToken(EMAIL);

        signup(token, "takennick", departmentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_DUPLICATED"));

        assertThat(redis.hasKey("auth:signup:" + token)).isTrue();
    }

    @Test
    @DisplayName("가입: 탈퇴한 회원의 닉네임은 다시 쓸 수 있다")
    void withdrawnNicknameCanBeReused() throws Exception {
        insertUser("TakenNick", "a".repeat(64), true);

        signup(signupToken(EMAIL), "TakenNick", departmentId).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("가입: 같은 이메일로 이미 가입했으면 다른 토큰이어도 409 AUTH_EMAIL_ALREADY_REGISTERED")
    void emailAlreadyRegistered() throws Exception {
        signup(signupToken(EMAIL), "가입왕", departmentId).andExpect(status().isCreated());

        signup(signupToken(EMAIL), "가입왕2", departmentId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("가입: 같은 토큰으로 동시에 가입하면 하나만 성공하고 나머지는 이메일 중복(AUTH_EMAIL_ALREADY_REGISTERED / uk_users_email_hash)")
    void concurrentSignupWithSameToken() throws Exception {
        String token = signupToken(EMAIL);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String nickname = "동시가입" + i;
            futures.add(executor.submit(() -> {
                start.await();
                return signupService.signup(new SignupRequest(token, nickname, PASSWORD, departmentId));
            }));
        }
        start.countDown();

        int succeeded = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        executor.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).hasSize(threads - 1).allSatisfy(SignupIntegrationTest::isEmailDuplicate);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE university_id = ?", Long.class,
                universityId)).isEqualTo(1);
    }

    // ===== 닉네임 확인 =====

    @Test
    @DisplayName("닉네임 확인: 대소문자만 달라도 사용 중이고, 탈퇴 회원의 닉네임과 없는 닉네임은 사용할 수 있다")
    void nicknameAvailability() throws Exception {
        insertUser("TakenNick", "a".repeat(64), false);
        insertUser("LeftNick", "b".repeat(64), true);

        assertAvailable("TAKENNICK", false);
        assertAvailable("leftnick", true);
        assertAvailable("새닉네임", true);
    }

    private void assertAvailable(String nickname, boolean expected) throws Exception {
        mockMvc.perform(get("/api/v1/users/nickname/availability").param("nickname", nickname))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(expected));
    }

    private static void isEmailDuplicate(Throwable failure) {
        if (failure instanceof BusinessException business) {
            assertThat(business.getErrorCode()).isEqualTo(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED);
            return;
        }
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ConstraintViolationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isNotNull();
        assertThat(((ConstraintViolationException) cause).getConstraintName()).isEqualTo("uk_users_email_hash");
    }

    /** 실제 API로 코드를 요청하고 메일로 받은 코드로 확인해 가입 토큰을 받는다 */
    private String verifyEmail(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendCode(anyString(), code.capture());

        String body = mockMvc.perform(post("/api/v1/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.signupToken");
    }

    /** 이메일 인증을 마친 상태를 만든다 (코드 확인이 저장하는 값과 같다) */
    private String signupToken(String email) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set("auth:signup:" + token, email);
        return token;
    }

    private ResultActions signup(String token, String nickname, long department) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"signupToken\":\"" + token + "\",\"nickname\":\"" + nickname + "\",\"password\":\""
                        + PASSWORD + "\",\"departmentId\":" + department + "}"));
    }

    private void insertUser(String nickname, String emailHash, boolean withdrawn) {
        jdbcTemplate.update("INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, "
                        + "approved_at, status, deleted_at) VALUES (?, ?, ?, 'enc', ?, 'pw', now(), ?, "
                        + (withdrawn ? "now()" : "NULL") + ")",
                universityId, departmentId, nickname, emailHash, withdrawn ? "WITHDRAWN" : "ACTIVE");
    }

    private long insert(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
