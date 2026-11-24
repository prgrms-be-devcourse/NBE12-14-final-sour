package com.back.nbe1214finalsour.domain.support.service;

import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 문의 대량 등록을 막는 고정 창 한도 (SCRUM-110, 계획 Q7·Q8).
 *
 * <ul>
 *   <li>회원은 {@code ratelimit:support:user:{userId}}로 센다. 학교 와이파이처럼 여러 학생이 한 IP를 쓰므로 IP로 세면
 *   다른 학생 때문에 막힌다.</li>
 *   <li>비회원은 {@code ratelimit:support:ip:{ipHash}}로 센다. IP 원문 대신 {@link EmailHasher#hashClientIp} 값을 쓴다.</li>
 * </ul>
 *
 * <p>{@code incr-with-ttl.lua}로 INCR과 첫 만료 설정을 한 번에 해 동시 요청도 한도를 넘지 못한다. 등록이 뒤에서 실패해도
 * 되돌리지 않는다. Redis 오류는 그대로 올려 요청을 실패시킨다.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SupportRateLimitProperties.class)
public class SupportRateLimiter {

    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of(
            new ClassPathResource("redis/incr-with-ttl.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final EmailHasher hasher;
    private final SupportRateLimitProperties properties;

    /** @throws BusinessException 429 {@code COMMON_TOO_MANY_REQUESTS} */
    public void checkMember(Long userId) {
        check(RedisKey.RATELIMIT_SUPPORT_USER.of(userId), "회원 userId=" + userId);
    }

    /** @throws BusinessException 429 {@code COMMON_TOO_MANY_REQUESTS} */
    public void checkGuest(String clientIp) {
        check(RedisKey.RATELIMIT_SUPPORT_IP.of(hasher.hashClientIp(clientIp)), "비회원 IP");
    }

    /**
     * 같은 IP의 이번 창 비회원 등록 수. 세지 않고 읽기만 한다 (SCRUM-119 봇 확인 기준). 키가 없으면 0
     */
    public long guestCount(String clientIp) {
        String value = redis.opsForValue().get(RedisKey.RATELIMIT_SUPPORT_IP.of(hasher.hashClientIp(clientIp)));
        return value == null ? 0L : Long.parseLong(value);
    }

    private void check(String key, String who) {
        Long count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(properties.window().toSeconds()));
        if (count != null && count > properties.maxRequests()) {
            throw new BusinessException(ErrorCode.COMMON_TOO_MANY_REQUESTS,
                    who + " 문의 등록 " + properties.maxRequests() + "건/" + properties.window() + " 초과");
        }
    }
}
