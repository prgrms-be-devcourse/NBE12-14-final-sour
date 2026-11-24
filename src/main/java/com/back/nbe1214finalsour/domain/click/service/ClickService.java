package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceStandingResolver;
import com.back.nbe1214finalsour.domain.click.dto.ClickApplyResponse;
import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.service.library.LibraryBonusChecker;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
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

@Service
@RequiredArgsConstructor
public class ClickService {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserRepository userRepository;
    private final RedisHandler redisHandler;
    private final ClickActionGuard clickActionGuard;
    private final LibraryBonusChecker libraryBonusChecker;
    private final AllianceStandingResolver allianceStandingResolver;
    private final UniversityCache universityCache;

    private static final RedisScript<List> APPLY_CLICK = RedisScript.of(new ClassPathResource("redis/apply-click.lua"), List.class);
    private static final BigDecimal LIBRARY_MULTIPLIER = new BigDecimal("1.2");

    /**
     * @param universityId 토큰의 학교 ID
     * @param departmentId 토큰의 학과 ID. {@code null}이면(학과 claim을 넣기 전에 발급된 토큰) DB 값을 쓴다
     */
    public ClickResponse click(Long userId, Long universityId, Long departmentId, ClickRequest request) {
        int clickCount = request.clickCount();
        String submissionId = request.submissionId();
        Double lat = request.latitude();
        Double lng = request.longitude();

        long score = clickActionGuard.verifyAndGetBaseScore(userId, submissionId, clickCount);

        if (departmentId == null) {
            departmentId = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                    .getDepartmentId();
        }

        // 아이템 적용 가져오기
        BigDecimal itemMultiplier = BigDecimal.valueOf(redisHandler.getHash(RedisKey.USER_EFFECT.of(userId), "multiplier", Double.class)
                .orElse(1.0));

        // 도서관 이스터에그
        boolean isInLibrary = libraryBonusChecker.isInLibrary(universityId, lat, lng);
        BigDecimal multiplierBigDecimal = itemMultiplier.multiply(isInLibrary ? LIBRARY_MULTIPLIER : BigDecimal.ONE);

        // 점수 계산
        score = multiplierBigDecimal.multiply(BigDecimal.valueOf(score))
                .setScale(0, RoundingMode.FLOOR).longValueExact();

        // 연합·대결 확인
        AllianceStandingResolver.AllianceStanding allianceStanding = allianceStandingResolver.resolve(universityId);

        // 4. Redis에 반영
        long nowSec = Instant.now().getEpochSecond();
        // 숨김 학교(운영팀용) 점수는 공개 학교 순위에 넣지 않는다. 순위·지도·어제 순위는 RANK_UNIV만 읽는다 (SCRUM-236)
        List<String> keys = List.of(
                RedisKey.univRank(universityCache.isHidden(universityId)),
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.DIRTY_UNIV.of(),
                RedisKey.DIRTY_DEPT.of(),
                RedisKey.DIRTY_USER.of(),
                RedisKey.CLICKS_UNIV_SECOND.of(universityId, nowSec),
                RedisKey.CLICKS_UNIV_SECOND.of(universityId, nowSec - 1),
                RedisKey.ALLIANCE_SCORE.of(allianceStanding.allianceId()),
                RedisKey.MATCH_SCORE.of(allianceStanding.matchId()),
                RedisKey.DIRTY_ALLIANCE.of(),
                RedisKey.DIRTY_MATCH.of()
        );

        List<String> r = stringRedisTemplate.execute(
                APPLY_CLICK,
                keys,
                String.valueOf(universityId),
                String.valueOf(departmentId),
                String.valueOf(userId),
                String.valueOf(score),
                String.valueOf(clickCount),
                String.valueOf(allianceStanding.allianceId()),
                allianceStanding.team(),
                String.valueOf(allianceStanding.matchId())
        );
        ClickApplyResponse clickApplyResponse = new ClickApplyResponse(
                Long.valueOf(r.get(0)),
                Long.valueOf(r.get(1)),
                Long.valueOf(r.get(2)),
                Long.valueOf(r.get(3)),
                Long.valueOf(r.get(4)));

        return ClickResponse.from(clickApplyResponse, clickCount, score, multiplierBigDecimal.doubleValue(), universityId, departmentId, isInLibrary);
    }
}
