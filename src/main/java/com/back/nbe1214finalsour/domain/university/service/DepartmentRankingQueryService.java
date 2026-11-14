package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentRankingResponse;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DepartmentRankingQueryService {

    private final UniversityRankingService rankingService;
    private final UniversityRepository universityRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional(readOnly = true)
    public DepartmentRankingResponse getDepartments(
            Long universityId,
            int page,
            int size,
            Long myUniversityId,
            Long myDepartmentId
    ) {
        // 1. 학교 존재 여부 검증
        if (!universityRepository.existsById(universityId)) {
            throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        }

        long offset = (long) page * size;

        // 2. Redis에서 학과 랭킹 조회
        long totalCount = rankingService.getDepartmentCount(universityId);

        List<RedisHandler.RankEntry> entries =
                rankingService.getDepartmentsRange(
                        universityId, offset, size
                );

        // 3. PostgreSQL에서 학과 정보 일괄 조회
        List<Long> ids = entries.stream()
                .map(RedisHandler.RankEntry::member)
                .toList();

        Map<Long, Department> departmentMap = ids.isEmpty()
                ? Map.of()
                : departmentRepository
                .findByUniversity_IdAndIdIn(universityId, ids)
                .stream()
                .collect(Collectors.toMap(
                        Department::getId,
                        Function.identity()
                ));

        // 4. 학과별 점수와 공동 순위 구성
        List<DepartmentRankingResponse.Item> items =
                new ArrayList<>();

        long rank = entries.isEmpty() ? 0
                : rankingService.getDepartmentRank(
                universityId,
                entries.get(0).member()
        ).orElse(offset + 1);

        Double previousScore = null;

        for (int i = 0; i < entries.size(); i++) {
            RedisHandler.RankEntry entry = entries.get(i);

            if (previousScore != null
                    && Double.compare(previousScore, entry.score()) != 0) {
                rank = offset + i + 1;
            }

            previousScore = entry.score();

            Department department = departmentMap.get(entry.member());

            if (department == null) {
                continue;
            }

            items.add(new DepartmentRankingResponse.Item(
                    rank,
                    department.getId(),
                    department.getName(),
                    department.getLogoUrl(),
                    (long) entry.score()
            ));
        }

        // 5. 로그인한 사용자의 소속 학과 순위
        DepartmentRankingResponse.MyDepartment myDepartment = null;

        boolean isMyUniversity = myUniversityId != null
                && myUniversityId.equals(universityId);

        if (isMyUniversity && myDepartmentId != null) {
            var myRank = rankingService.getDepartmentRank(
                    universityId, myDepartmentId
            );
            var myScore = rankingService.getDepartmentScore(
                    universityId, myDepartmentId
            );

            if (myRank.isPresent() && myScore.isPresent()) {
                myDepartment = new DepartmentRankingResponse.MyDepartment(
                        myRank.getAsLong(),
                        myDepartmentId,
                        (long) myScore.getAsDouble()
                );
            }
        }

        // 6. 페이지 정보와 함께 반환
        boolean hasNext = offset + size < totalCount;

        return new DepartmentRankingResponse(
                items,
                page,
                size,
                totalCount,
                hasNext,
                myDepartment
        );
    }
}