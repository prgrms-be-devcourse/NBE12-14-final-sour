package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 인증 API의 IP별 횟수를 Redis에 둔다 (SCRUM-100). 키에는 IP 원문 대신 {@code EmailHasher.hashClientIp} 값을 넣는다.
 *
 * <p>로그인은 {@link LoginAttemptStore}처럼 비밀번호를 확인하기 <b>전에</b> 자리를 잡는다. 확인한 뒤에 세면 같은 IP에서
 * 동시에 보낸 요청이 모두 한도 검사를 통과한다. 비밀번호가 맞으면 {@link #releaseLogin}으로 돌려놓아 실패만 남게 한다.
 */
@Repository
@RequiredArgsConstructor
public class AuthIpLimitStore {

    /**
     * 횟수가 최대보다 작으면 1 올려 돌려준다. KEYS: 횟수. ARGV: 최대 횟수, 만료(초, 처음 만들 때만).
     * 반환: -1 한도 도달, 1 이상 이번 순번
     */
    private static final RedisScript<Long> ACQUIRE = RedisScript.of("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then
                return -1
            end
            count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return count
            """, Long.class);

    /** 키가 남아 있으면 1 줄이고, 0 이하가 되면 지운다. 만료된 뒤에 TTL 없는 음수 키가 생기지 않게 한다. KEYS: 횟수 */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            local count = redis.call('DECR', KEYS[1])
            if count <= 0 then
                redis.call('DEL', KEYS[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redis;

    /** 로그인 시도 자리를 잡는다. 한도에 닿았으면 {@code false} */
    public boolean tryAcquireLogin(String ipHash, int maxFailures, Duration window) {
        return acquire(RedisKey.AUTH_IP_LOGIN_FAIL.of(ipHash), maxFailures, window);
    }

    /** 비밀번호가 맞은 시도를 실패 횟수에서 뺀다 */
    public void releaseLogin(String ipHash) {
        redis.execute(RELEASE, List.of(RedisKey.AUTH_IP_LOGIN_FAIL.of(ipHash)));
    }

    /** 인증 코드 요청 횟수를 센다. 한도에 닿았으면 {@code false} */
    public boolean tryAcquireEmailCode(String ipHash, int maxRequests, Duration window) {
        return acquire(RedisKey.AUTH_IP_EMAIL_CODE.of(ipHash), maxRequests, window);
    }

    private boolean acquire(String key, int max, Duration window) {
        Long result = redis.execute(ACQUIRE, List.of(key), String.valueOf(max), String.valueOf(window.toSeconds()));
        return result != null && result > 0;
    }
}
