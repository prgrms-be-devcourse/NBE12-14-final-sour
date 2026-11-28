package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceStandingResolver;
import com.back.nbe1214finalsour.domain.click.dto.AssistRequest;
import com.back.nbe1214finalsour.domain.click.dto.AssistResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 연합 학교 지원 (추가 명세 §3-3, 결정 7, SCRUM-187).
 *
 * <p>대상 학교 +⌊base × 0.3⌋, 내 기여도 +⌊base × 아이템 배율⌋ (base = clickCount × clickPower). 내 학교·학과 점수는
 * 그대로다. 도서관 보너스는 내 학교 도서관 위치 보너스라 적용하지 않는다. 대결 점수는 클릭도 아직 쌓는 곳이 없어 반영하지 않는다.
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
    private final AllianceStandingResolver allianceStandingResolver;

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

        ClickActionGuard.Score scoreVo = clickActionGuard.verifyAndGetBaseScore(userId, request.submissionId(), clickCount, ClickActionGuard.ClickType.ASSIST);
        long assistScore = scoreVo.score() * 3 / 10;
        BigDecimal itemMultiplier = BigDecimal.valueOf(redisHandler.getHash(RedisKey.USER_EFFECT.of(userId), "multiplier", Double.class)
                .orElse(1.0));
        long contribScore = itemMultiplier.multiply(BigDecimal.valueOf(scoreVo.score()))
                .setScale(0, RoundingMode.FLOOR).longValueExact();

        // 연합·대결 확인
        AllianceStandingResolver.AllianceStanding allianceStanding = allianceStandingResolver.resolve(targetUniversityId);

        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = List.of(RedisKey.univRank(universityCache.isHidden(targetUniversityId)), RedisKey.RANK_USER.of(universityId),
                RedisKey.DIRTY_UNIV.of(), RedisKey.DIRTY_USER.of(),
                RedisKey.ALLIANCE_SCORE.of(allianceStanding.allianceId()), RedisKey.MATCH_SCORE.of(allianceStanding.matchId()),
                RedisKey.DIRTY_ALLIANCE.of(), RedisKey.DIRTY_MATCH.of(),
                RedisKey.ASSISTS_UNIV_SECOND.of(targetUniversityId, nowSec), RedisKey.ASSISTS_UNIV_SECOND.of(targetUniversityId, nowSec - 1),
                RedisKey.CLICKS_UNIV_SECOND.of(targetUniversityId, nowSec - 1), RedisKey.ATTACKS_UNIV_SECOND.of(targetUniversityId, nowSec - 1));

        // 숨김 학교도 연합 학교로 지원받지만, 점수는 공개 학교 순위에 넣지 않는다 (SCRUM-238)
        List<String> r = stringRedisTemplate.execute(
                APPLY_ASSIST,
                keys,
                String.valueOf(targetUniversityId),
                String.valueOf(assistScore),
                String.valueOf(userId),
                String.valueOf(contribScore),
                String.valueOf(allianceStanding.allianceId()),
                allianceStanding.team(),
                String.valueOf(allianceStanding.matchId()),
                String.valueOf(clickCount)
        );
        return new AssistResponse(
                (long) clickCount,
                assistScore,
                new AssistResponse.AssistUniversityResponse(targetUniversityId, targetName,
                        Long.valueOf(r.get(0)), Long.valueOf(r.get(1))),
                Long.valueOf(r.get(2)), scoreVo.criticalIndex(), scoreVo.damageRateIndex(),
                Long.valueOf(r.get(3)), Long.valueOf(r.get(4)), Long.valueOf(r.get(5)), nowSec - 1);
    }
}
