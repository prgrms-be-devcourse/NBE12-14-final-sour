package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.click.dto.ClickApplyResponse;
import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
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
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClickService {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserRepository userRepository;
    private final RedisHandler redisHandler;
    private final ClickActionGuard clickActionGuard;
    private final LibraryBonusChecker libraryBonusChecker;
    private final Optional<MatchScoredAction> matchScoring;

    private static final RedisScript<List> APPLY_CLICK = RedisScript.of(new ClassPathResource("redis/apply-click.lua"), List.class);
    private static final BigDecimal LIBRARY_MULTIPLIER = new BigDecimal("1.2");

    /**
     * @param universityId 토큰의 학교 ID
     * @param departmentId 토큰의 학과 ID. {@code null}이면(학과 claim을 넣기 전에 발급된 토큰) DB 값을 쓴다
     */
    public ClickResponse click(Long userId, Long universityId, Long departmentId, ClickRequest request) {
        if (matchScoring.isPresent()) {
            return matchScoring.orElseThrow().apply(userId, universityId, request.submissionId(), request.clickCount(),
                    (base, attempt) -> applyClick(userId, universityId, departmentId, request, base, attempt));
        }
        long base = clickActionGuard.verifyAndGetBaseScore(userId, request.submissionId(), request.clickCount());
        return applyClick(userId, universityId, departmentId, request, base, null);
    }

    private ClickResponse applyClick(Long userId, Long universityId, Long departmentId, ClickRequest request,
                                     long score, MatchScoredAction.Attempt attempt) {
        int clickCount = request.clickCount();
        Double lat = request.latitude();
        Double lng = request.longitude();

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

        // 4. Redis에 반영
        long nowSec = Instant.now().getEpochSecond();
        List<String> keys = List.of(
                RedisKey.RANK_UNIV.of(),
                RedisKey.RANK_DEPT.of(universityId),
                RedisKey.RANK_USER.of(universityId),
                RedisKey.DIRTY_UNIV.of(),
                RedisKey.DIRTY_DEPT.of(),
                RedisKey.DIRTY_USER.of(),
                RedisKey.CLICKS_UNIV_SECOND.of(universityId, nowSec),
                RedisKey.CLICKS_UNIV_SECOND.of(universityId, nowSec - 1)
        );

        String[] args = {universityId.toString(), departmentId.toString(), userId.toString(),
                Long.toString(score), Integer.toString(clickCount)};
        List<String> r = attempt == null ? stringRedisTemplate.execute(APPLY_CLICK, keys, (Object[]) args)
                : matchScoring.orElseThrow().execute(true, attempt, keys, args);
        ClickApplyResponse clickApplyResponse = new ClickApplyResponse(
                Long.valueOf(r.get(0)),
                Long.valueOf(r.get(1)),
                Long.valueOf(r.get(2)),
                Long.valueOf(r.get(3)),
                Long.valueOf(r.get(4)));

        return ClickResponse.from(clickApplyResponse, clickCount, score, multiplierBigDecimal.doubleValue(), universityId, departmentId, isInLibrary);
    }
}
