package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.abuse.service.AbuseVerificationService;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.repository.UserClickerLevelCache;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class ClickActionGuard {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserStatusCache userStatusCache;
    private final UserRepository userRepository;
    private final ActionRateLimiter actionRateLimiter;
    private final ClickCountLimiter clickCountLimiter;
    private final ClickerService clickerService;
    private final AbuseVerificationService abuseVerificationService;
    private final UserClickerLevelCache userClickerLevelCache;
    private final ScoreRestoreService scoreRestoreService;
    private final ObjectMapper objectMapper;
    @Value("${click.critical-rate}")
    private int criticalRate;
    @Value("${click.min-damage-rate}")
    private int minDamageRate;
    @Value("${click.max-damage-rate}")
    private int maxDamageRate;

    public Score verifyAndGetBaseScore(Long userId, String submissionId, int clickCount, ClickType clickType) {
        if (!scoreRestoreService.isReady()) {
            throw new BusinessException(ErrorCode.RESTORE_SCORE_RESTORING);
        }

        try {
            checkActive(userId);
            // 어뷰징 의심 표시가 있으면 봇 확인 전까지 막는다 (SCRUM-118). 중복 제출 확인보다 앞이라 막힌 요청은 submissionId를 쓰지 않는다
            abuseVerificationService.requireNotFlagged(userId);
            checkDuplicate(userId, submissionId);
            actionRateLimiter.check(userId);
            clickCountLimiter.check(userId, clickCount);
        } catch (BusinessException e) {
            // 한도 초과를 어뷰징 의심 신호로 쓴다 (SCRUM-118). 넘은 요청 자체는 지금처럼 429
            if (e.getErrorCode() == ErrorCode.CLICK_TOO_FREQUENT || e.getErrorCode() == ErrorCode.CLICK_COUNT_EXCEEDED) {
                abuseVerificationService.flag(userId);
            }
            throw e;
        }

        return loadBaseScore(userId, clickCount, clickType);
    }

    private Score loadBaseScore(Long userId, int clickCount, ClickType clickType) {
        List<Integer> newCriticalIndex = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            if (ThreadLocalRandom.current().nextInt(100) < criticalRate) {
                newCriticalIndex.add(i);
            }
        }

        String criticalString = stringRedisTemplate.opsForValue()
                .setGet(getCriticalKey(userId, clickType),
                        objectMapper.writeValueAsString(newCriticalIndex), Duration.ofSeconds(30));
        List<Integer> criticalIndex = new ArrayList<>();
        if (criticalString != null && !criticalString.isEmpty()) {
            criticalIndex = objectMapper.readValue(criticalString, new TypeReference<>() {
            });
        }

        // 레벨 원본은 Redis. 키가 없을 때만 DB 레벨로 채운다 (원본을 덮어쓰지 않는다)
        long clickerLevel = userClickerLevelCache.loadIfAbsent(userId);

        Clicker clicker = clickerService.getClickerById(clickerLevel);

        List<Integer> newDamageRateIndex = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            newDamageRateIndex.add(ThreadLocalRandom.current().nextInt(minDamageRate, maxDamageRate + 1));
        }

        String damageRateString = stringRedisTemplate.opsForValue()
                .setGet(getDamageRateKey(userId, clickType),
                        objectMapper.writeValueAsString(newDamageRateIndex), Duration.ofSeconds(30));
        List<Integer> damageRateIndex = new ArrayList<>();
        if (damageRateString != null && !damageRateString.isEmpty()) {
            damageRateIndex = objectMapper.readValue(damageRateString, new TypeReference<>() {
            });
        }
        long sum = 0;
        for (int i = 0; i < clickCount; i++) {
            // 70 ~ 130% 데미지, 2배 크리티컬
            int damageRate = damageRateIndex.size() > i ? damageRateIndex.get(i) : 100;
            int critical = criticalIndex != null && criticalIndex.contains(i) ? 2 : 1;
            sum += (long) clicker.getClickPower() * damageRate * critical;
        }

        return new Score(sum / 100, newCriticalIndex, newDamageRateIndex);
    }

    private static @NonNull String getDamageRateKey(Long userId, ClickType clickType) {
        return clickType.equals(ClickType.CLICK) ? RedisKey.CLICKS_DAMAGE_RATE.of(userId) : clickType.equals(ClickType.ATTACK) ? RedisKey.ATTACKS_DAMAGE_RATE.of(userId) : RedisKey.ASSISTS_DAMAGE_RATE.of(userId);
    }

    private static @NonNull String getCriticalKey(Long userId, ClickType clickType) {
        return clickType.equals(ClickType.CLICK) ? RedisKey.CLICKS_CRITICAL.of(userId) : clickType.equals(ClickType.ATTACK) ? RedisKey.ATTACKS_CRITICAL.of(userId) : RedisKey.ASSISTS_CRITICAL.of(userId);
    }

    private boolean checkActive(Long userId) {
        UserStatus userStatus = userStatusCache.find(userId).orElse(null);
        if (userStatus == null) {
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            userStatus = userStatusCache.fillIfAbsent(userId, user.getStatus());
        }
        if (userStatus != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        return true;
    }

    private void checkDuplicate(Long userId, String submissionId) {
        if (Boolean.FALSE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(RedisKey.SUBMIT_DEDUPE.of(userId, submissionId), submissionId, Duration.ofSeconds(10L)))) {
            throw new BusinessException(ErrorCode.CLICK_DUPLICATE_SUBMISSION);
        }
    }

    enum ClickType {
        CLICK("click"),
        ATTACK("attack"),
        ASSIST("assist");
        public final String type;

        ClickType(String type) {
            this.type = type;
        }
    }

    record Score(long score, List<Integer> criticalIndex, List<Integer> damageRateIndex) {
    }
}
