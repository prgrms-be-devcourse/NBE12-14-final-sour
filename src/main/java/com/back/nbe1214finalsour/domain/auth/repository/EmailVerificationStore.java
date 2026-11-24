package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 학교 이메일 인증 상태를 Redis에 둔다 (회원 MVP 계획 §4-3).
 *
 * <p>값은 문자열로만 다루도록 {@link StringRedisTemplate}을 쓴다. 팀 {@code RedisTemplate<String, Object>}는 값을 JSON으로
 * 직렬화해 {@code INCR}·{@code HINCRBY}와 섞어 쓸 수 없다 (GitHub #25).
 * 키에는 이메일 원문 대신 {@code EmailHasher.hash} 값을 넣는다. 읽고 고치는 연산은 Lua 스크립트로 원자적으로 처리한다.
 */
@Repository
@RequiredArgsConstructor
public class EmailVerificationStore {

    /** 하루 횟수 키의 수명. 날짜가 키에 들어 있으므로 그날이 끝난 뒤에만 사라지면 된다 (그날 처음 만든 때부터 하루) */
    static final Duration DAILY_KEY_TTL = Duration.ofDays(1);

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    /** {@code UUID.randomUUID().toString()} 형식 (EmailVerificationService가 발급) */
    private static final Pattern SIGNUP_TOKEN_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** 이전 코드와 실패 횟수를 지우고 새 코드를 만료 시간과 함께 저장한다. ARGV: 코드, 만료(초) */
    private static final RedisScript<Long> SAVE_CODE = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'code', ARGV[1], 'attempts', '0')
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /**
     * 코드를 확인한다. ARGV: 입력 코드, 최대 실패 횟수.
     * 반환: -1 코드 없음(만료), 0 일치(코드 삭제), 1 이상 누적 실패 횟수(최대에 닿으면 코드 삭제).
     * 키가 없을 때 {@code HINCRBY}로 만료 없는 키가 생기지 않도록 확인과 증가를 한 스크립트에서 한다.
     */
    private static final RedisScript<Long> VERIFY_CODE = RedisScript.of("""
            local code = redis.call('HGET', KEYS[1], 'code')
            if not code then
                return -1
            end
            if code == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 0
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then
                redis.call('DEL', KEYS[1])
            end
            return attempts
            """, Long.class);

    /** 횟수를 1 올리고, 처음 만든 키에만 만료를 건다. ARGV: 만료(초) */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    /** 쿨다운을 시작한다. 이미 쿨다운 중이면 {@code false} ({@code SET NX EX}) */
    public boolean tryStartCooldown(String emailHash, Duration cooldown) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(RedisKey.AUTH_EMAIL_COOLDOWN.of(emailHash), "1", cooldown));
    }

    /** 그날(한국 날짜) 요청 횟수를 1 올리고 올린 값을 돌려준다 */
    public long incrementDailyCount(String emailHash, LocalDate day) {
        Long count = redis.execute(INCREMENT_WITH_TTL, List.of(dailyKey(emailHash, day)),
                String.valueOf(DAILY_KEY_TTL.toSeconds()));
        return count == null ? 0 : count;
    }

    public void saveCode(String emailHash, String code, Duration ttl) {
        redis.execute(SAVE_CODE, List.of(RedisKey.AUTH_EMAIL_CODE.of(emailHash)), code, String.valueOf(ttl.toSeconds()));
    }

    /** 메일을 보내지 못했을 때 저장한 코드와 쿨다운을 되돌린다. 하루 횟수는 되돌리지 않는다 */
    public void discardCode(String emailHash) {
        redis.delete(List.of(RedisKey.AUTH_EMAIL_CODE.of(emailHash), RedisKey.AUTH_EMAIL_COOLDOWN.of(emailHash)));
    }

    public CodeCheck verifyCode(String emailHash, String code, int maxAttempts) {
        Long result = redis.execute(VERIFY_CODE, List.of(RedisKey.AUTH_EMAIL_CODE.of(emailHash)), code,
                String.valueOf(maxAttempts));
        if (result == null || result < 0) {
            return CodeCheck.EXPIRED;
        }
        if (result == 0) {
            return CodeCheck.MATCHED;
        }
        return result >= maxAttempts ? CodeCheck.ATTEMPTS_EXCEEDED : CodeCheck.MISMATCHED;
    }

    /** 가입 토큰에 정규화한 이메일을 묶어 저장한다. 회원가입(단계 6)이 읽고, 가입이 끝나면 지운다 */
    public void saveSignupToken(String signupToken, String email, Duration ttl) {
        redis.opsForValue().set(RedisKey.AUTH_SIGNUP_TOKEN.of(signupToken), email, ttl);
    }

    /**
     * 가입 토큰에 묶인 정규화한 이메일. 만료됐거나 없는 토큰이면 비어 있다.
     * 토큰은 요청에서 온 값이라, 발급 형식(UUID)이 아니면 Redis를 조회하지 않고 없는 토큰으로 본다.
     * {@link RedisKey#of}는 ':'가 섞인 인자를 예외로 거부하므로, 이 확인이 없으면 400 대신 서버 오류가 된다.
     */
    public Optional<String> findSignupEmail(String signupToken) {
        if (signupToken == null || !SIGNUP_TOKEN_FORMAT.matcher(signupToken).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(RedisKey.AUTH_SIGNUP_TOKEN.of(signupToken)));
    }

    public void deleteSignupToken(String signupToken) {
        redis.delete(RedisKey.AUTH_SIGNUP_TOKEN.of(signupToken));
    }

    static String dailyKey(String emailHash, LocalDate day) {
        return RedisKey.AUTH_EMAIL_DAILY.of(emailHash, day.format(DAY));
    }

    public enum CodeCheck {
        MATCHED,
        MISMATCHED,
        EXPIRED,
        ATTEMPTS_EXCEEDED
    }
}
