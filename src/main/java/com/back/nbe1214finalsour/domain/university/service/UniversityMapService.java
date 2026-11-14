package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UniversityMapService {

    private static final int MAX_LIMIT = 300;
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final UniversityRankingService rankingService;
    private final UniversityRepository universityRepository;
    private final RedisHandler redisHandler;

    public List<UniversityMapResponse> getUniversities(int limit) {

        String cacheKey = RedisKey.CACHE_MAP_UNIVERSITIES.of();

        // 1. Redis에 캐시가 있는지 확인
        var cached = redisHandler.get(
                cacheKey,
                UniversityMapResponse[].class
        );

        List<UniversityMapResponse> universities;

        if (cached.isPresent()) {
            universities = Arrays.asList(cached.get());
        } else {
            // 2. 캐시가 없으면 실제 데이터를 조회
            universities = loadUniversities();

            // 3. 조회 결과를 Redis에 5초 동안 저장
            redisHandler.setTtl(
                    cacheKey,
                    universities.toArray(UniversityMapResponse[]::new),
                    CACHE_TTL
            );
        }

        // 4. 요청한 개수만큼 반환
        return universities.subList(
                0,
                Math.min(limit, universities.size())
        );
    }

    private List<UniversityMapResponse> loadUniversities() {

        // 1. Redis에서 점수 상위 300개 학교 조회
        List<RedisHandler.RankEntry> entries =
                rankingService.getUniversitiesRange(0, MAX_LIMIT);

        if (entries.isEmpty()) {
            return List.of();
        }

        // 2. 학교 ID 추출
        List<Long> universityIds = entries.stream()
                .map(RedisHandler.RankEntry::member)
                .toList();

        // 3. PostgreSQL에서 학교 정보 일괄 조회
        Map<Long, University> universityMap =
                universityRepository.findAllById(universityIds)
                        .stream()
                        .collect(Collectors.toMap(
                                University::getId,
                                Function.identity()
                        ));

        // 4. Redis 순서를 유지하면서 응답 생성
        List<UniversityMapResponse> result = new ArrayList<>();

        Double previousScore = null;
        long rank = 0;

        for (int i = 0; i < entries.size(); i++) {
            RedisHandler.RankEntry entry = entries.get(i);

            // 동점인 학교는 같은 순위
            if (previousScore == null
                    || Double.compare(previousScore, entry.score()) != 0) {
                rank = i + 1L;
            }

            previousScore = entry.score();

            University university = universityMap.get(entry.member());

            if (university == null) {
                continue;
            }

            result.add(new UniversityMapResponse(
                    rank,
                    university.getId(),
                    university.getName(),
                    university.getLogoUrl(),
                    (long) entry.score(),
                    university.getLibraryLat(),
                    university.getLibraryLng()
            ));
        }

        return result;
    }
}
