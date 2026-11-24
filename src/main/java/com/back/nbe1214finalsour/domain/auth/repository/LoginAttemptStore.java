package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 로그인 연속 실패 횟수와 잠금을 Redis에 둔다 (회원 MVP 계획 §4-3, API 명세서 로그인 "5번 연속 실패하면 10분").
 *
 * <p>비밀번호를 확인하기 <b>전에</b> 시도 횟수를 올려 자리를 잡는다. 확인한 뒤에 올리면 같은 이메일로 동시에 보낸 요청이
 * 모두 잠금 검사를 통과해, 10분에 5번보다 많이 비밀번호를 대입할 수 있다. 성공하면 횟수를 지운다.
 * 키에는 이메일 원문 대신 {@code EmailHasher.hash} 값을 넣는다. 가입하지 않은 이메일도 같은 규칙으로 센다.
 */
@Repository
@RequiredArgsConstructor
public class LoginAttemptStore {

    /**
     * 잠겨 있지 않으면 시도 횟수를 1 올려 돌려준다. KEYS: 잠금, 횟수. ARGV: 최대 횟수, 횟수 키 만료(초).
     * 반환: -1 잠김(잠금 키가 있거나, 진행 중인 시도까지 합쳐 최대를 넘음), 1 이상 이번 시도 순번.
     */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return -1
            end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then
                redis.call('EXPIRE', KEYS[2], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
                return -1
            end
            return count
            """, Long.class);

    /** 잠그고 횟수를 지운다. KEYS: 잠금, 횟수. ARGV: 잠금 시간(초) */
    private static final RedisScript<Long> LOCK = RedisScript.of("""
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * 로그인 시도 자리를 잡는다. 횟수 키는 첫 시도부터 {@code window} 동안 남는다.
     *
     * @return 이번 시도 순번 (1부터). 잠겼으면 -1
     */
    public long tryAcquire(String emailHash, int maxAttempts, Duration window) {
        Long result = redis.execute(ACQUIRE, keys(emailHash), String.valueOf(maxAttempts),
                String.valueOf(window.toSeconds()));
        return result == null ? -1 : result;
    }

    /** {@code lockDuration} 동안 로그인을 막는다 */
    public void lock(String emailHash, Duration lockDuration) {
        redis.execute(LOCK, keys(emailHash), String.valueOf(lockDuration.toSeconds()));
    }

    /** 비밀번호가 맞으면 연속 실패 횟수를 지운다 */
    public void reset(String emailHash) {
        redis.delete(RedisKey.AUTH_LOGIN_FAIL.of(emailHash));
    }

    private static List<String> keys(String emailHash) {
        return List.of(RedisKey.AUTH_LOGIN_LOCK.of(emailHash), RedisKey.AUTH_LOGIN_FAIL.of(emailHash));
    }
}
