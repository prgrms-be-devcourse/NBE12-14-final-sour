package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityMemberReader;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UniversityMemberServiceTest {

    @Mock
    private UniversityMemberReader universityMemberReader;

    @Mock
    private RedisHandler redisHandler;

    @InjectMocks
    private UniversityMemberService universityMemberService;

    @Test
    @DisplayName("Redis 캐시가 있으면 DB를 조회하지 않는다")
    void getMemberCount_cached() {

        // given
        Long universityId = 12L;
        String key = RedisKey.CACHE_UNIV_MEMBER_COUNT.of(universityId);

        given(redisHandler.get(key, Long.class))
                .willReturn(Optional.of(498L));

        // when
        long result = universityMemberService.getMemberCount(universityId);

        // then
        assertThat(result).isEqualTo(498L);

        verify(universityMemberReader, never())
                .countActiveMembers(universityId);
    }

    @Test
    @DisplayName("Redis 캐시가 없으면 DB 조회 후 1분간 저장한다")
    void getMemberCount_cacheMiss() {

        // given
        Long universityId = 12L;
        String key = RedisKey.CACHE_UNIV_MEMBER_COUNT.of(universityId);

        given(redisHandler.get(key, Long.class))
                .willReturn(Optional.empty());

        given(universityMemberReader.countActiveMembers(universityId))
                .willReturn(498L);

        // when
        long result = universityMemberService.getMemberCount(universityId);

        // then
        assertThat(result).isEqualTo(498L);

        verify(universityMemberReader)
                .countActiveMembers(universityId);

        verify(redisHandler)
                .setTtl(key, 498L, Duration.ofMinutes(1));
    }
}