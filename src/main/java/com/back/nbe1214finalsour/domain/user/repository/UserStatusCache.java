package com.back.nbe1214finalsour.domain.user.repository;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 클릭이 요청마다 DB를 읽지 않고 회원 상태를 확인하도록 두는 캐시 ({@link RedisKey#USER_STATUS}). 원본은 DB {@code users.status}.
 *
 * <ul>
 *   <li>채우기: 캐시가 없을 때 DB 값을 읽은 쪽(클릭)이 {@link #fillIfAbsent}로 채운다. 이미 있는 값은 덮지 않는다</li>
 *   <li>덮어쓰기: <b>회원 상태를 바꾸는 코드는 커밋 뒤 {@link #put}을 부른다</b> (탈퇴: {@code UserStatusCacheSyncListener}).
 *       관리자 정지·차단도 같은 방법을 써야 한다</li>
 *   <li>TTL: 설정 {@code jwt.access-token-ttl}(access 토큰 유효 시간). 갱신을 빠뜨린 경로가 있어도 TTL 뒤에는 DB 값으로 돌아온다</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class UserStatusCache {

    private final StringRedisTemplate redis;
    private final JwtProperties jwtProperties;

    public Optional<UserStatus> find(Long userId) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKey.USER_STATUS.of(userId))).map(UserStatus::valueOf);
    }

    /**
     * 캐시가 없을 때만 DB에서 읽은 값을 채운다 ({@code SET NX}). 탈퇴 직전에 {@code ACTIVE}를 읽은 요청이 늦게 써도
     * 탈퇴가 넣은 값을 덮지 않는다.
     *
     * @return 캐시에 들어 있는 상태. 다른 쪽이 먼저 넣었으면 그 값
     */
    public UserStatus fillIfAbsent(Long userId, UserStatus status) {
        Boolean stored = redis.opsForValue().setIfAbsent(RedisKey.USER_STATUS.of(userId), status.name(), ttl());
        if (Boolean.TRUE.equals(stored)) {
            return status;
        }
        return find(userId).orElse(status);
    }

    /** 상태를 바꾼 쪽이 기존 값과 상관없이 덮어쓴다 */
    public void put(Long userId, UserStatus status) {
        redis.opsForValue().set(RedisKey.USER_STATUS.of(userId), status.name(), ttl());
    }

    private Duration ttl() {
        return jwtProperties.accessTokenTtl();
    }
}
