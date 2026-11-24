package com.back.nbe1214finalsour.domain.abuse.service;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.turnstile.TurnstileResult;
import com.back.nbe1214finalsour.global.turnstile.TurnstileVerifier;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 클릭 어뷰징 의심 표시와 Turnstile 봇 확인 (SCRUM-118, 계획 §3-2).
 *
 * <ul>
 *   <li>클릭 한도(행동 10초 12번, 클릭 1분 900번)를 넘으면 {@code abuse:verify:user:{userId}}에 표시를 남긴다 (P1·P2).</li>
 *   <li>표시가 있는 동안 클릭은 403 {@code ABUSE_VERIFICATION_REQUIRED}다.</li>
 *   <li>확인을 통과하거나 Cloudflare에 닿지 못하면(fail-open) 표시를 지운다. 거절이면 403 {@code ABUSE_VERIFICATION_FAILED}.</li>
 *   <li>{@code turnstile.enabled=false}면 아무것도 하지 않는다. 지금과 동작이 같다 (P10).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AbuseVerificationProperties.class)
public class AbuseVerificationService {

    /** 클릭 화면 위젯의 action (P8). 다른 화면에서 받은 토큰을 막는다 */
    public static final String CLICK_ACTION = "abuse_click";

    private static final String FLAG_VALUE = "1";
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of(
            new ClassPathResource("redis/incr-with-ttl.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final TurnstileVerifier turnstileVerifier;
    private final AbuseVerificationProperties properties;

    /** @throws BusinessException 403 {@code ABUSE_VERIFICATION_REQUIRED} 의심 표시가 있을 때 */
    public void requireNotFlagged(Long userId) {
        if (!turnstileVerifier.enabled()) {
            return;
        }
        if (Boolean.TRUE.equals(redis.hasKey(RedisKey.ABUSE_VERIFY_USER.of(userId)))) {
            throw new BusinessException(ErrorCode.ABUSE_VERIFICATION_REQUIRED, "의심 표시가 있는 회원의 클릭 userId=" + userId);
        }
    }

    /** 클릭 한도를 넘은 회원에게 의심 표시를 남긴다. 이미 있으면 유지 시간을 새로 잰다 */
    public void flag(Long userId) {
        if (!turnstileVerifier.enabled()) {
            return;
        }
        redis.opsForValue().set(RedisKey.ABUSE_VERIFY_USER.of(userId), FLAG_VALUE, properties.flagTtl());
    }

    /**
     * Turnstile 토큰으로 의심 표시를 지운다. 표시가 없어도 성공한다 (멱등).
     *
     * <p>표시가 있었으면 클릭 한도 카운터도 지운다. 그대로 두면 확인 직후 클릭이 아직 끝나지 않은 한도 창에 걸려
     * 다시 429와 표시를 받는다. 표시가 없을 때는 지우지 않는다. 확인만으로 한도를 초기화하지 못하게 하기 위해서다.
     *
     * @param token    위젯 토큰. 위젯을 못 불러왔으면 없을 수 있다 (P6)
     * @param clientIp {@code getRemoteAddr()} 값 (P13)
     * @throws BusinessException 429 {@code COMMON_TOO_MANY_REQUESTS} 확인 시도 한도 초과 (P7),
     *                           403 {@code ABUSE_VERIFICATION_FAILED} Cloudflare가 거절
     */
    public void verify(Long userId, @Nullable String token, @Nullable String clientIp) {
        if (!turnstileVerifier.enabled()) {
            return;
        }
        checkAttempts(userId);

        TurnstileResult result = turnstileVerifier.verify(token, CLICK_ACTION, clientIp);
        if (result == TurnstileResult.REJECTED) {
            throw new BusinessException(ErrorCode.ABUSE_VERIFICATION_FAILED, "클릭 봇 확인 거절 userId=" + userId);
        }
        if (result == TurnstileResult.UNAVAILABLE) {
            log.warn("클릭 봇 확인을 Cloudflare 연결 불가로 통과시킴(fail-open) userId={}", userId);
        }

        if (Boolean.TRUE.equals(redis.delete(RedisKey.ABUSE_VERIFY_USER.of(userId)))) {
            redis.delete(List.of(RedisKey.RATELIMIT_ACTION.of(userId), RedisKey.RATELIMIT_CLICK.of(userId)));
        }
    }

    private void checkAttempts(Long userId) {
        Long count = redis.execute(INCR_WITH_TTL, List.of(RedisKey.RATELIMIT_ABUSE_VERIFY.of(userId)),
                String.valueOf(properties.attemptWindow().toSeconds()));
        if (count != null && count > properties.maxAttempts()) {
            throw new BusinessException(ErrorCode.COMMON_TOO_MANY_REQUESTS,
                    "클릭 봇 확인 " + properties.maxAttempts() + "번/" + properties.attemptWindow() + " 초과 userId=" + userId);
        }
    }
}
