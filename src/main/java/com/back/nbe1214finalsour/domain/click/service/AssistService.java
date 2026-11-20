package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.click.dto.AssistRequest;
import com.back.nbe1214finalsour.domain.click.dto.AssistResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 연합 학교 지원 (추가 명세 §3-3, 결정 7, SCRUM-187).
 *
 * <p>대상 학교 +⌊base × 0.3⌋, 내 기여도 +⌊base × 아이템 배율⌋ (base = clickCount × clickPower). 내 학교·학과 점수는
 * 그대로다. 도서관 보너스는 내 학교 도서관 위치 보너스라 적용하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AssistService {

    private static final RedisScript<List> APPLY_ASSIST =
            RedisScript.of(new ClassPathResource("redis/apply-assist.lua"), List.class);

    private final ClickActionGuard clickActionGuard;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisHandler redisHandler;
    private final UniversityCache universityCache;
    private final AllianceAllyChecker allianceAllyChecker;
    private final Optional<MatchScoredAction> matchScoring;

    public AssistResponse assist(Long userId, Long universityId, AssistRequest request) {
        int clickCount = request.clickCount();
        Long targetUniversityId = request.targetUniversityId();

        // 공격과 같이 대상 검증을 먼저 한다. 거절된 요청은 제출 ID와 요청 한도를 쓰지 않는다
        if (universityId.equals(targetUniversityId)) {
            throw new BusinessException(ErrorCode.ASSIST_NOT_ALLY, "내 학교 지원 userId=" + userId);
        }
        String targetName = universityCache.getName(targetUniversityId);
        if (!allianceAllyChecker.areAllies(universityId, targetUniversityId)) {
            throw new BusinessException(ErrorCode.ASSIST_NOT_ALLY,
                    "동맹 아님 userId=" + userId + ", target=" + targetUniversityId);
        }

        if (matchScoring.isPresent()) {
            return matchScoring.orElseThrow().apply(userId, targetUniversityId, request.submissionId(), clickCount,
                    (base, attempt) -> applyAssist(userId, universityId, targetUniversityId, targetName,
                            clickCount, base, attempt));
        }
        long base = clickActionGuard.verifyAndGetBaseScore(userId, request.submissionId(), clickCount);
        return applyAssist(userId, universityId, targetUniversityId, targetName, clickCount, base, null);
    }

    private AssistResponse applyAssist(Long userId, Long universityId, Long targetUniversityId, String targetName,
                                       int clickCount, long base, MatchScoredAction.Attempt attempt) {
        long assistScore = base * 3 / 10;
        BigDecimal itemMultiplier = BigDecimal.valueOf(redisHandler.getHash(RedisKey.USER_EFFECT.of(userId), "multiplier", Double.class)
                .orElse(1.0));
        long contribScore = itemMultiplier.multiply(BigDecimal.valueOf(base))
                .setScale(0, RoundingMode.FLOOR).longValueExact();

        List<String> keys = List.of(RedisKey.RANK_UNIV.of(), RedisKey.RANK_USER.of(universityId),
                RedisKey.DIRTY_UNIV.of(), RedisKey.DIRTY_USER.of());
        String[] args = {targetUniversityId.toString(), Long.toString(assistScore), userId.toString(),
                Long.toString(contribScore)};
        List<String> r = attempt == null ? stringRedisTemplate.execute(APPLY_ASSIST, keys, (Object[]) args)
                : matchScoring.orElseThrow().execute(false, attempt, keys, args);
        return new AssistResponse(
                (long) clickCount,
                assistScore,
                new AssistResponse.AssistUniversityResponse(targetUniversityId, targetName,
                        Long.valueOf(r.get(0)), Long.valueOf(r.get(1))),
                Long.valueOf(r.get(2)));
    }
}
