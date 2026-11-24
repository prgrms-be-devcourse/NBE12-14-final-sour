package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeResponse;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyResponse;
import com.back.nbe1214finalsour.domain.auth.mail.VerificationMailSender;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;

/**
 * 학교 이메일 인증을 PostgreSQL·Redis(Testcontainers)로 확인한다. 메일은 가짜 빈으로 받아 코드를 꺼낸다.
 * DB 데이터는 테스트 트랜잭션이 끝나면 롤백되고, Redis {@code auth:*} 키는 테스트마다 지운다.
 */
@IntegrationTest
@Transactional
class EmailVerificationServiceTest {

    private static final String DOMAIN = "scrum64-test.ac.kr";
    private static final String EMAIL = "student@" + DOMAIN;
    /** IP 한도(20번/1시간)에 닿지 않도록 테스트마다 Redis {@code auth:*}를 지운다 */
    private static final String CLIENT_IP = "203.0.113.7";
    /** 2026-11-14 00:30 KST. UTC 날짜(11-13)와 한국 날짜(11-14)가 다른 시각 */
    private static final Instant NOW = Instant.parse("2026-11-13T15:30:00Z");

    @Autowired
    private EmailVerificationService service;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private VerificationMailSender mailSender;
    @Autowired
    private Clock clock;

    private long universityId;
    private long departmentId;
    private String emailHash;

    @BeforeEach
    void setUp() {
        clearRedis();
        given(clock.instant()).willReturn(NOW);
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('인증테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        jdbcTemplate.update("INSERT INTO uni_domain (university_id, domain) VALUES (?, ?)", universityId, DOMAIN);
        emailHash = emailHasher.hash(EMAIL);
    }

    @AfterEach
    void clearRedis() {
        Set<String> keys = redis.keys("auth:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ===== 코드 요청 =====

    @Test
    @DisplayName("코드 요청: 학교와 유효 시간 300초를 응답하고, 정규화한 이메일로 6자리 코드를 보내며 같은 코드를 Redis에 5분 저장한다")
    void requestCode() {
        EmailCodeResponse response = service.requestCode("  Student@SCRUM64-Test.ac.kr ", CLIENT_IP);

        assertThat(response.university().id()).isEqualTo(universityId);
        assertThat(response.university().name()).isEqualTo("인증테스트대");
        assertThat(response.expiresInSeconds()).isEqualTo(300);

        String code = sentCode();
        assertThat(code).matches("\\d{6}");
        assertThat(redis.opsForHash().get(codeKey(), "code")).isEqualTo(code);
        assertThat(redis.opsForHash().get(codeKey(), "attempts")).isEqualTo("0");
        assertThat(redis.getExpire(codeKey())).isBetween(1L, 300L);
        assertThat(redis.getExpire("auth:email:cooldown:" + emailHash)).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("코드 요청: 하루 횟수 키의 날짜는 한국 날짜다 (UTC 11-13 15:30 → 20261114)")
    void dailyKeyUsesKoreanDate() {
        service.requestCode(EMAIL, CLIENT_IP);

        String key = "auth:email:daily:" + emailHash + ":20261114";
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(redis.getExpire(key)).isBetween(1L, 86_400L);
    }

    @Test
    @DisplayName("코드 요청: 등록되지 않은 도메인이면 400 AUTH_UNSUPPORTED_DOMAIN, 메일과 쿨다운 없음")
    void unsupportedDomain() {
        assertErrorCode(() -> service.requestCode("student@unknown-school.ac.kr", CLIENT_IP), ErrorCode.AUTH_UNSUPPORTED_DOMAIN);

        verify(mailSender, never()).sendCode(anyString(), anyString());
        assertThat(redis.keys("auth:*")).isEmpty();
    }

    @Test
    @DisplayName("코드 요청: 도메인은 정확히 같아야 한다 (하위 도메인은 다른 도메인)")
    void subdomainIsNotSupported() {
        assertErrorCode(() -> service.requestCode("student@mail." + DOMAIN, CLIENT_IP), ErrorCode.AUTH_UNSUPPORTED_DOMAIN);
    }

    @Test
    @DisplayName("코드 요청: 탈퇴하지 않은 회원의 이메일이면 409 AUTH_EMAIL_ALREADY_REGISTERED")
    void alreadyRegistered() {
        saveUser();

        assertErrorCode(() -> service.requestCode(EMAIL.toUpperCase(), CLIENT_IP), ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED);
        verify(mailSender, never()).sendCode(anyString(), anyString());
    }

    @Test
    @DisplayName("코드 요청: 탈퇴한 회원의 이메일은 다시 인증할 수 있다")
    void withdrawnEmailCanRequestAgain() {
        User user = saveUser();
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", user.getId());

        service.requestCode(EMAIL, CLIENT_IP);

        verify(mailSender).sendCode(anyString(), anyString());
    }

    @Test
    @DisplayName("코드 요청: 60초 안에 다시 요청하면 429 AUTH_CODE_COOLDOWN, 메일은 한 번만 보낸다")
    void cooldown() {
        service.requestCode(EMAIL, CLIENT_IP);

        assertErrorCode(() -> service.requestCode(EMAIL, CLIENT_IP), ErrorCode.AUTH_CODE_COOLDOWN);
        verify(mailSender, times(1)).sendCode(anyString(), anyString());
    }

    @Test
    @DisplayName("코드 요청: 하루 10번까지 보내고 11번째는 429 AUTH_CODE_COOLDOWN")
    void dailyLimit() {
        for (int i = 0; i < 10; i++) {
            service.requestCode(EMAIL, CLIENT_IP);
            redis.delete("auth:email:cooldown:" + emailHash);
        }

        assertErrorCode(() -> service.requestCode(EMAIL, CLIENT_IP), ErrorCode.AUTH_CODE_COOLDOWN);
        verify(mailSender, times(10)).sendCode(anyString(), anyString());
    }

    @Test
    @DisplayName("코드 요청: 메일을 보내지 못하면 예외를 그대로 던지고, 코드와 쿨다운을 지워 바로 다시 요청할 수 있다")
    void mailFailureDiscardsCode() {
        willThrow(new MailSendException("SMTP 연결 실패")).given(mailSender).sendCode(anyString(), anyString());

        assertThatThrownBy(() -> service.requestCode(EMAIL, CLIENT_IP)).isInstanceOf(MailSendException.class);
        assertThat(redis.hasKey(codeKey())).isFalse();
        assertThat(redis.hasKey("auth:email:cooldown:" + emailHash)).isFalse();

        willDoNothing().given(mailSender).sendCode(anyString(), anyString());
        service.requestCode(EMAIL, CLIENT_IP);
        assertThat(redis.hasKey(codeKey())).isTrue();
    }

    // ===== 코드 확인 =====

    @Test
    @DisplayName("코드 확인: 맞으면 가입 토큰(UUID)을 발급해 정규화한 이메일과 30분 묶고, 코드는 지운다")
    void verifyCode() {
        service.requestCode(EMAIL, CLIENT_IP);
        String code = sentCode();

        EmailVerifyResponse response = service.verifyCode(" STUDENT@" + DOMAIN.toUpperCase(), code);

        assertThat(UUID.fromString(response.signupToken())).isNotNull();
        assertThat(response.university().id()).isEqualTo(universityId);
        assertThat(response.expiresInSeconds()).isEqualTo(1800);
        String signupKey = "auth:signup:" + response.signupToken();
        assertThat(redis.opsForValue().get(signupKey)).isEqualTo(EMAIL);
        assertThat(redis.getExpire(signupKey)).isBetween(1L, 1800L);
        assertThat(redis.hasKey(codeKey())).isFalse();
    }

    @Test
    @DisplayName("코드 확인: 한 번 쓴 코드로 다시 확인하면 410 AUTH_CODE_EXPIRED")
    void codeIsSingleUse() {
        service.requestCode(EMAIL, CLIENT_IP);
        String code = sentCode();
        service.verifyCode(EMAIL, code);

        assertErrorCode(() -> service.verifyCode(EMAIL, code), ErrorCode.AUTH_CODE_EXPIRED);
    }

    @Test
    @DisplayName("코드 확인: 요청한 코드가 없거나 만료되었으면 410 AUTH_CODE_EXPIRED")
    void codeExpired() {
        assertErrorCode(() -> service.verifyCode(EMAIL, "123456"), ErrorCode.AUTH_CODE_EXPIRED);
        assertThat(redis.hasKey(codeKey())).withFailMessage("확인 실패가 만료 없는 키를 만들면 안 됩니다").isFalse();
    }

    @Test
    @DisplayName("코드 확인: 틀리면 400 AUTH_INVALID_CODE, 5번째로 틀리면 429 AUTH_CODE_ATTEMPTS_EXCEEDED이고 코드가 폐기된다")
    void attemptsExceeded() {
        service.requestCode(EMAIL, CLIENT_IP);
        String code = sentCode();
        String wrong = wrongCode(code);

        for (int i = 1; i <= 4; i++) {
            assertErrorCode(() -> service.verifyCode(EMAIL, wrong), ErrorCode.AUTH_INVALID_CODE);
            assertThat(redis.opsForHash().get(codeKey(), "attempts")).isEqualTo(String.valueOf(i));
        }
        assertErrorCode(() -> service.verifyCode(EMAIL, wrong), ErrorCode.AUTH_CODE_ATTEMPTS_EXCEEDED);

        assertThat(redis.hasKey(codeKey())).isFalse();
        assertErrorCode(() -> service.verifyCode(EMAIL, code), ErrorCode.AUTH_CODE_EXPIRED);
    }

    @Test
    @DisplayName("코드 확인: 코드를 다시 요청하면 실패 횟수가 0부터 다시 센다")
    void newCodeResetsAttempts() {
        service.requestCode(EMAIL, CLIENT_IP);
        String first = sentCode();
        assertErrorCode(() -> service.verifyCode(EMAIL, wrongCode(first)), ErrorCode.AUTH_INVALID_CODE);
        redis.delete("auth:email:cooldown:" + emailHash);

        service.requestCode(EMAIL, CLIENT_IP);

        assertThat(redis.opsForHash().get(codeKey(), "attempts")).isEqualTo("0");
    }

    private String sentCode() {
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender, atLeastOnce()).sendCode(email.capture(), code.capture());
        assertThat(email.getValue()).isEqualTo(EMAIL);
        return code.getValue();
    }

    private static String wrongCode(String code) {
        char last = code.charAt(5);
        return code.substring(0, 5) + (last == '9' ? '0' : (char) (last + 1));
    }

    private String codeKey() {
        return "auth:email:code:" + emailHash;
    }

    private User saveUser() {
        return userRepository.saveAndFlush(User.create(universityId, departmentId, "인증테스트", EMAIL, emailHash,
                "{bcrypt}encoded", OffsetDateTime.now()));
    }

    private static void assertErrorCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
