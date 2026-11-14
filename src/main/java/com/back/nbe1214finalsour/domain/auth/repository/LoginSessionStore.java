package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;

    public Optional<String> find(Long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKey.AUTH_SESSION.of(userId)));
    }

    /** 현재 세션으로 정하고 TTL을 다시 설정한다 */
    public void put(Long userId, String sessionId) {
        redis.opsForValue().set(RedisKey.AUTH_SESSION.of(userId), sessionId, jwtProperties.refreshTokenTtl());
    }
}
