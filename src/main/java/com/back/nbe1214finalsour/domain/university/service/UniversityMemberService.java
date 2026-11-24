package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityMemberReader;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UniversityMemberService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final UniversityMemberReader universityMemberReader;
    private final RedisHandler redisHandler;

    public long getMemberCount(Long universityId) {

        // 1. 학교별 Redis 캐시 키 생성
        String key = RedisKey.CACHE_UNIV_MEMBER_COUNT.of(universityId);

        // 2. Redis에서 캐시된 회원 수 조회
        Optional<Long> cachedCount = redisHandler.get(key, Long.class);

        if (cachedCount.isPresent()) {
            return cachedCount.get();
        }

        // 3. 캐시가 없으면 PostgreSQL 조회
        long memberCount =
                universityMemberReader.countActiveMembers(universityId);

        // 4. 조회한 회원 수를 Redis에 1분간 저장
        redisHandler.setTtl(key, memberCount, CACHE_TTL);

        return memberCount;
    }
}