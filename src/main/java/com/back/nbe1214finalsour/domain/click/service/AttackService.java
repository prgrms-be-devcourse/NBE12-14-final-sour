package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.click.dto.AttackApplyResponse;
import com.back.nbe1214finalsour.domain.click.dto.AttackRequest;
import com.back.nbe1214finalsour.domain.click.dto.AttackResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.service.rival.RivalChecker;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttackService {

    private final ClickActionGuard clickActionGuard;
    private final StringRedisTemplate stringRedisTemplate;
    private final RivalChecker rivalChecker;
    private final UniversityCache universityCache;
    private final AllianceAllyChecker allianceAllyChecker;

    private static final RedisScript<List> APPLY_ATTACK = RedisScript.of(new ClassPathResource("redis/apply-attack.lua"), List.class);

    public AttackResponse attack(Long userId, Long universityId, AttackRequest request) {
        int clickCount = request.clickCount();
        String submissionId = request.submissionId();
        Long targetUniversityId = request.targetUniversityId();

        // 공격할 학교와 본인 학교 비교
        if (universityId.equals(targetUniversityId)) {
            throw new BusinessException(ErrorCode.ATTACK_OWN_UNIVERSITY);
        }

        String targetName = universityCache.getName(targetUniversityId);
        // 대상 검증 시점의 소속을 사용한다. 거절된 공격은 제출 ID·요청 한도를 소비하지 않는다.
        // TODO: DB를 타지 않고 Redis에서 조회하도록 변경
        if (allianceAllyChecker.areAllies(universityId, targetUniversityId)) {
            throw new BusinessException(ErrorCode.ATTACK_ALLY_UNIVERSITY);
        }

        // 점수 구하기
        ClickActionGuard.Score scoreVo = clickActionGuard.verifyAndGetBaseScore(userId, submissionId, clickCount, ClickActionGuard.ClickType.ATTACK);

        // 경쟁 학교 인지 계산 + 최종 점수 계산(* 0.3)
        boolean isRival = rivalChecker.isRival(universityId, targetUniversityId);
        long requestedDamage = isRival ? scoreVo.score() * 33 / 100
                : scoreVo.score() * 3 / 10;

        // Redis에 반영
        // 숨김 학교도 일반 학교처럼 공격받지만, 점수는 공개 학교 순위에 넣지 않는다 (SCRUM-238)
        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = List.of(
                RedisKey.univRank(universityCache.isHidden(targetUniversityId)),
                RedisKey.DIRTY_UNIV.of(),
                RedisKey.SCORE_USER_ATTACK.of(),
                RedisKey.DIRTY_USER.of(),
                RedisKey.ATTACKS_UNIV_SECOND.of(targetUniversityId, nowSec),
                RedisKey.ATTACKS_UNIV_SECOND.of(targetUniversityId, nowSec - 1),
                RedisKey.CLICKS_UNIV_SECOND.of(targetUniversityId, nowSec - 1),
                RedisKey.ASSISTS_UNIV_SECOND.of(targetUniversityId, nowSec - 1)
        );

        List<String> r = stringRedisTemplate.execute(
                APPLY_ATTACK,
                keys,
                String.valueOf(targetUniversityId),
                String.valueOf(requestedDamage),
                String.valueOf(userId),
                String.valueOf(clickCount)
        );
        AttackApplyResponse attackApplyResponse = new AttackApplyResponse(
                Long.valueOf(r.get(0)),
                Long.valueOf(r.get(1)),
                Long.valueOf(r.get(2)),
                Long.valueOf(r.get(3)),
                Long.valueOf(r.get(4)),
                Long.valueOf(r.get(5)),
                Long.valueOf(r.get(6)));

        return AttackResponse.from(
                attackApplyResponse, clickCount, isRival, targetUniversityId,
                targetName, scoreVo.criticalIndex(), scoreVo.damageRateIndex(), nowSec - 1);
    }
}
