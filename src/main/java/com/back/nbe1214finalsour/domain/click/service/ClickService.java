package com.back.nbe1214finalsour.domain.click.service;

import com.back.nbe1214finalsour.domain.click.dto.ClickApplyResponse;
import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClickService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ActionRateLimiter actionRateLimiter;
    private final ClickCountLimiter clickCountLimiter;
    private final ClickerService clickerService;
    private final UserRepository userRepository;
    private final RedisHandler redisHandler;

    private static final RedisScript<List> APPLY_CLICK = RedisScript.of(new ClassPathResource("redis/apply-click.lua"), List.class);

    /**
     * @param universityId 토큰의 학교 ID
     * @param departmentId 토큰의 학과 ID. {@code null}이면(학과 claim을 넣기 전에 발급된 토큰) DB 값을 쓴다
     */
    public ClickResponse click(Long userId, Long universityId, Long departmentId, ClickRequest request) {
        int clickCount = request.clickCount();
        String submissionId = request.submissionId();

        // 회원이 활성화인 경우만 가능
        String userStatus = stringRedisTemplate.opsForValue().get(RedisKey.USER_STATUS.of(userId));
        if (userStatus == null) {
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            userStatus = String.valueOf(user.getStatus());
            stringRedisTemplate.opsForValue().set(RedisKey.USER_STATUS.of(userId), userStatus);
        }
        if (!userStatus.equals("ACTIVE")) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }

        // 1. 중복 제출 확인 및 저장
        Boolean isRequested = stringRedisTemplate.opsForValue().setIfAbsent(RedisKey.SUBMIT_DEDUPE.of(userId, submissionId), submissionId, Duration.ofSeconds(10L));

        // 위에서 저장이 안됐으면 중복 요청임
        if (Boolean.FALSE.equals(isRequested)) {
            throw new BusinessException(ErrorCode.CLICK_DUPLICATE_SUBMISSION);
        }

        // 2. 횟수·간격 검증 (클릭·공격·지원 모두 합쳐서 10초에 12회 요청이 왔는가?) + (1분 동안의 클릿 횟수가 900번 이하인가?)
        actionRateLimiter.check(userId);
        clickCountLimiter.check(userId, clickCount);

        // 3. 인정 점수
        int score = clickCount;

        // 클리커 데미지 가져오기
        String clickerLevel = stringRedisTemplate.opsForValue().get(RedisKey.USER_CLICKER.of(userId));

        // 회원은 레벨 캐시가 없거나, 토큰에 학과 ID가 없을 때(SCRUM-81 배포 전 발급)만 DB에서 읽는다
        if (clickerLevel == null || departmentId == null) {
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
            // 레디스에 정보가 없으면 레디스에 저장
            if (clickerLevel == null) {
                stringRedisTemplate.opsForValue().set(RedisKey.USER_CLICKER.of(userId), String.valueOf(user.getClickerId()));
                clickerLevel = String.valueOf(user.getClickerId());
            }
            if (departmentId == null) {
                departmentId = user.getDepartmentId();
            }
        }

        Clicker clicker = clickerService.getClickerById(Long.valueOf(clickerLevel));

        score *= clicker.getClickPower();

        // 아이템 적용 가져오기
        Double multiplier = redisHandler.getHash(RedisKey.USER_EFFECT.of(userId), "multiplier", Double.class)
                .orElse(1.0);
        score = (int) Math.floor(score * multiplier);

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

        List<String> r = stringRedisTemplate.execute(
                APPLY_CLICK,
                keys,
                String.valueOf(universityId),
                String.valueOf(departmentId),
                String.valueOf(userId),
                String.valueOf(score),
                String.valueOf(clickCount)
        );
        ClickApplyResponse clickApplyResponse = new ClickApplyResponse(
                Long.valueOf(r.get(0)),
                Long.valueOf(r.get(1)),
                Long.valueOf(r.get(2)),
                Long.valueOf(r.get(3)),
                Long.valueOf(r.get(4)));

        return ClickResponse.from(clickApplyResponse, clickCount, score, multiplier, universityId, departmentId);
    }
}
