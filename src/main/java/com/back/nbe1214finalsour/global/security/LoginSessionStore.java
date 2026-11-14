package com.back.nbe1214finalsour.global.security;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 회원의 현재 로그인 세션 ID (계정당 1기기, SCRUM-83). 키 {@link RedisKey#AUTH_SESSION}.
 *
 * <p>로그인이 새 세션으로 덮어쓴다. access 토큰의 {@code sid}·refresh 토큰 행의 {@code session_id}가 이 값과 다르면
 * 다른 기기에서 로그인해 끊긴 세션이다. TTL은 refresh 토큰과 같은 {@code jwt.refresh-token-ttl}이다.
 */
@Repository
@RequiredArgsConstructor
public class LoginSessionStore {

    /** 현재 세션이 ARGV[1]일 때만 지운다. KEYS: 세션 키. 반환: 지운 키 수 */
    private static final RedisScript<Long> DELETE_IF_MATCHES = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;

    public Optional<String> find(Long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKey.AUTH_SESSION.of(userId)));
    }

    /** 현재 세션으로 정하고 TTL을 다시 설정한다 */
    public void put(Long userId, String sessionId) {
        redis.opsForValue().set(RedisKey.AUTH_SESSION.of(userId), sessionId, jwtProperties.refreshTokenTtl());
    }

    /**
     * 로그아웃: 현재 세션이 {@code sessionId}일 때만 지운다. 이미 다른 기기에서 로그인했으면 그 세션은 두어야 하므로
     * 비교와 삭제를 한 번에 한다 (Lua).
     */
    public void deleteIfMatches(Long userId, String sessionId) {
        redis.execute(DELETE_IF_MATCHES, List.of(RedisKey.AUTH_SESSION.of(userId)), sessionId);
    }

    /** 탈퇴·정지: 어느 세션이든 지운다 */
    public void delete(Long userId) {
        redis.delete(RedisKey.AUTH_SESSION.of(userId));
    }
}
