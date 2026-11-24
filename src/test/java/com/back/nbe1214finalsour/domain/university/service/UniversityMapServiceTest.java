package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UniversityMapServiceTest {

    @Mock
    private UniversityRankingService rankingService;

    @Mock
    private UniversityRepository universityRepository;

    @Mock
    private RedisHandler redisHandler;

    @InjectMocks
    private UniversityMapService mapService;

    @Test
    @DisplayName("캐시가 없으면 점수순으로 학교를 조회하고 5초간 캐싱한다")
    void getUniversities_cacheMiss() {

        String cacheKey = RedisKey.CACHE_MAP_UNIVERSITIES.of();

        // 1. Redis 캐시가 없는 상황
        given(redisHandler.get(
                cacheKey,
                UniversityMapResponse[].class
        )).willReturn(Optional.empty());

        // 2. Redis 학교 랭킹 준비
        given(rankingService.getUniversitiesRange(0, 300))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(1L, 1000.0),
                        new RedisHandler.RankEntry(2L, 1000.0),
                        new RedisHandler.RankEntry(3L, 900.0)
                ));

        // 3. PostgreSQL 학교 정보 준비
        University seoul = mock(University.class);
        given(seoul.getId()).willReturn(1L);
        given(seoul.getName()).willReturn("서울대학교");
        given(seoul.getLibraryLat())
                .willReturn(new BigDecimal("37.123456"));
        given(seoul.getLibraryLng())
                .willReturn(new BigDecimal("127.123456"));

        University korea = mock(University.class);
        given(korea.getId()).willReturn(2L);
        given(korea.getName()).willReturn("고려대학교");

        University yonsei = mock(University.class);
        given(yonsei.getId()).willReturn(3L);
        given(yonsei.getName()).willReturn("연세대학교");

        // DB가 순위와 다른 순서로 반환하는 상황
        given(universityRepository.findAllById(
                List.of(1L, 2L, 3L)
        )).willReturn(List.of(yonsei, korea, seoul));

        // 4. 실제 테스트 대상 메서드 실행
        List<UniversityMapResponse> result =
                mapService.getUniversities(2);

        // 5. 결과 검증
        assertThat(result).hasSize(2);

        assertThat(result)
                .extracting(UniversityMapResponse::name)
                .containsExactly("서울대학교", "고려대학교");

        assertThat(result)
                .extracting(UniversityMapResponse::rank)
                .containsExactly(1L, 1L);

        assertThat(result.get(0).totalScore())
                .isEqualTo(1000L);

        assertThat(result.get(0).latitude())
                .isEqualByComparingTo("37.123456");

        // 좌표가 없는 학교는 null
        assertThat(result.get(1).latitude()).isNull();
        assertThat(result.get(1).longitude()).isNull();

        // 6. 전체 조회 결과를 5초간 캐싱했는지 검증
        verify(redisHandler).setTtl(
                eq(cacheKey),
                any(UniversityMapResponse[].class),
                eq(Duration.ofSeconds(5))
        );
    }

    @Test
    @DisplayName("캐시가 있으면 DB를 재조회하지 않고 요청한 개수만 반환한다")
    void getUniversities_cacheHit() {

        String cacheKey = RedisKey.CACHE_MAP_UNIVERSITIES.of();

        // 1. 이미 캐시에 저장된 학교 3개 준비
        UniversityMapResponse[] cached = {
                new UniversityMapResponse(
                        1L, 1L, "서울대학교", null,
                        1000L, null, null
                ),
                new UniversityMapResponse(
                        2L, 2L, "고려대학교", null,
                        900L, null, null
                ),
                new UniversityMapResponse(
                        3L, 3L, "연세대학교", null,
                        800L, null, null
                )
        };

        // 2. Redis 캐시에 데이터가 있다고 가정
        given(redisHandler.get(
                cacheKey,
                UniversityMapResponse[].class
        )).willReturn(Optional.of(cached));

        // 3. 학교 2개만 요청
        List<UniversityMapResponse> result =
                mapService.getUniversities(2);

        // 4. 요청한 2개만 반환되는지 확인
        assertThat(result).hasSize(2);

        assertThat(result)
                .extracting(UniversityMapResponse::name)
                .containsExactly("서울대학교", "고려대학교");

        // 5. 랭킹과 DB를 다시 조회하지 않았는지 확인
        verifyNoInteractions(
                rankingService,
                universityRepository
        );

        // 6. 이미 캐시가 있으므로 다시 저장하지 않아야 함
        verify(redisHandler, never()).setTtl(
                eq(cacheKey),
                any(UniversityMapResponse[].class),
                any(Duration.class)
        );
    }
}
