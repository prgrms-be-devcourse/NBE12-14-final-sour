package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.dto.UniversityRankingResponse;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UniversityRankingQueryService {

    private final UniversityRankingService rankingService;
    private final UniversityRepository universityRepository;

    public UniversityRankingResponse getUniversities(
            int page, int size, Long myUniversityId
    ) {
        long offset = (long) page * size;
        long totalCount = rankingService.getUniversityCount();

        // 1. Redis에서 현재 페이지의 학교 점수를 조회한다.
        List<RedisHandler.RankEntry> entries =
                rankingService.getUniversitiesRange(offset, size);

        // 2. 학교 ID들을 PostgreSQL에서 한 번에 조회한다.
        List<Long> ids = entries.stream()
                .map(RedisHandler.RankEntry::member)
                .toList();

        Map<Long, University> universityMap =
                universityRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(
                                University::getId,
                                Function.identity()
                        ));

        // 3. Redis 순서를 유지하면서 응답 목록을 만든다.
        List<UniversityRankingResponse.Item> items =
                new ArrayList<>();

        long rank = entries.isEmpty() ? 0
                : rankingService.getUniversityRank(
                entries.get(0).member()
        ).orElse(offset + 1);

        Double previousScore = null;

        for (int i = 0; i < entries.size(); i++) {
            RedisHandler.RankEntry entry = entries.get(i);

            if (i > 0 && Double.compare(
                    previousScore, entry.score()) != 0) {
                rank = offset + i + 1;
            }

            previousScore = entry.score();

            University university = universityMap.get(
                    entry.member()
            );

            if (university == null) {
                continue;
            }

            items.add(new UniversityRankingResponse.Item(
                    rank,
                    university.getId(),
                    university.getName(),
                    university.getLogoUrl(),
                    (long) entry.score()
            ));
        }

        // 4. 로그인한 경우 내 학교 순위를 조회한다.
        UniversityRankingResponse.MyUniversity myUniversity = null;

        if (myUniversityId != null) {
            var myRank = rankingService.getUniversityRank(
                    myUniversityId
            );
            var myScore = rankingService.getUniversityScore(
                    myUniversityId
            );

            if (myRank.isPresent() && myScore.isPresent()) {
                myUniversity = new UniversityRankingResponse.MyUniversity(
                        myRank.getAsLong(),
                        myUniversityId,
                        (long) myScore.getAsDouble()
                );
            }
        }

        // 5. 페이지 정보를 포함해서 반환한다.
        boolean hasNext = offset + size < totalCount;

        return new UniversityRankingResponse(
                items, page, size, totalCount, hasNext, myUniversity
        );
    }

}