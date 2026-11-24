package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityDetailResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityContributorReader;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UniversityQueryService {

    private final UniversityRepository universityRepository;
    private final DepartmentRepository departmentRepository;
    private final UniversityRankingService universityRankingService;
    private final UniversityContributorReader universityContributorReader;
    private final UniversityMemberService universityMemberService;
    private final AllianceAllyChecker allianceAllyChecker;
    private final RankSnapshotService rankSnapshotService;

    public List<UniversitySearchResponse> searchUniversities(
            String keyword,
            int size
    ) {
        return universityRepository
                .findByHiddenFalseAndNameContainingIgnoreCaseOrderByNameAsc(
                        keyword,
                        PageRequest.of(0, size)
                )
                .stream()
                .map(UniversitySearchResponse::new)
                .toList();
    }

    public List<DepartmentResponse> getDepartments(Long universityId){

        if(!universityRepository.existsById(universityId)){
            throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        }
        return departmentRepository
                .findByUniversity_IdOrderByNameAsc(universityId)
                .stream()
                .map(DepartmentResponse::new)
                .toList();
    }

    public University findUniversity(Long universityId){
        return universityRepository.findById(universityId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNIV_NOT_FOUND)
                );
    }

    public List<UniversityDetailResponse.TopDepartment> getTopDepartments(
            Long universityId
    ) {
        List<Department> departments =
                departmentRepository.findByUniversity_IdOrderByTotalScoreDescIdAsc(
                        universityId,
                        PageRequest.of(0, 3)
                );

        List<UniversityDetailResponse.TopDepartment> result =
                new ArrayList<>();

        Long previousScore = null;
        long rank = 0;

        for (int i = 0; i < departments.size(); i++) {
            Department department = departments.get(i);

            if (!department.getTotalScore().equals(previousScore)) {
                rank = i + 1L;
            }

            result.add(new UniversityDetailResponse.TopDepartment(
                    rank,
                    department.getId(),
                    department.getName(),
                    department.getTotalScore()
            ));

            previousScore = department.getTotalScore();
        }

        return result;
    }

    public Optional<UniversityScoreRank> getUniversityScoreRank(
            Long universityId
    ) {
        // 공개 메서드 단독 호출 시에도 학교 존재 여부를 검증한다.
        findUniversity(universityId);
        return readUniversityScoreRank(universityId);
    }

    private Optional<UniversityScoreRank> readUniversityScoreRank(Long universityId) {
        // Redis에서 학교 점수 조회
        var score = universityRankingService.getUniversityScore(universityId);

        if (score.isEmpty()) {
            return Optional.empty();
        }

        // 3. Redis에서 학교 순위 조회
        var rank = universityRankingService.getUniversityRank(universityId);

        if (rank.isEmpty()) {
            return Optional.empty();
        }

        // 4. 점수와 순위를 하나의 객체로 반환
        return Optional.of(new UniversityScoreRank(
                (long) score.getAsDouble(),
                rank.getAsLong()
        ));
    }

    public record UniversityScoreRank(
            Long totalScore,
            Long rank
    ) {
    }


    public List<UniversityDetailResponse.TopDepartment> getTopDepartmentsFromRedis(
            Long universityId
    ) {
        // 1. Redis에서 상위 학과 3개 조회
        List<RedisHandler.RankEntry> entries =
                universityRankingService.getTopDepartments(universityId);

        if (entries.isEmpty()) {
            return List.of();
        }

        // 2. Redis에서 가져온 학과 ID 추출
        List<Long> departmentIds = entries.stream()
                .map(RedisHandler.RankEntry::member)
                .toList();

        // 3. PostgreSQL에서 해당 학교의 학과 정보 조회
        Map<Long, Department> departmentMap =
                departmentRepository.findByUniversity_IdAndIdIn(
                        universityId, departmentIds
                ).stream().collect(Collectors.toMap(
                        Department::getId,
                        Function.identity()
                ));

        // 4. Redis 순서를 유지하면서 DTO 생성
        List<UniversityDetailResponse.TopDepartment> result =
                new ArrayList<>();

        Double previousScore = null;
        long rank = 0;

        for (int i = 0; i < entries.size(); i++) {
            RedisHandler.RankEntry entry = entries.get(i);

            Department department = departmentMap.get(entry.member());

            if (department == null) {
                // Redis에 남은 학과 ID가 DB에 없다면 DB 기준 상위 학과로 대체한다.
                // 서로 다른 기준의 점수와 순위를 섞지 않는다.
                return getTopDepartments(universityId);
            }

            if (previousScore == null
                    || Double.compare(previousScore, entry.score()) != 0) {
                rank = i + 1L;
            }

            result.add(new UniversityDetailResponse.TopDepartment(
                    rank,
                    department.getId(),
                    department.getName(),
                    (long) entry.score()
            ));

            previousScore = entry.score();
        }

        return result;
    }

    public List<UniversityDetailResponse.TopContributor> getTopContributorsFromRedis(
            Long universityId
    ) {
        // 상위 3명부터 조회하고, 비활성/탈퇴 회원이 있으면 다음 순위 구간을 가져온다.
        // Redis ZSET은 회원 상태를 모르므로 DB에서 ACTIVE 회원만 선별한다.
        List<UniversityDetailResponse.TopContributor> result = new ArrayList<>(3);
        long offset = 0;
        Double previousScore = null;
        long rank = 0;
        List<RedisHandler.RankEntry> entries =
                universityRankingService.getTopContributors(universityId);

        while (!entries.isEmpty() && result.size() < 3) {
            List<Long> userIds = entries.stream()
                    .map(RedisHandler.RankEntry::member)
                    .toList();
            Map<Long, String> nicknames =
                    universityContributorReader.findNicknames(universityId, userIds);

            for (RedisHandler.RankEntry entry : entries) {
                // 제외 회원도 Redis 원래 순위에는 포함된다. 동점이면 공동 순위를 유지한다.
                if (previousScore == null
                        || Double.compare(previousScore, entry.score()) != 0) {
                    rank = offset + 1;
                }
                previousScore = entry.score();
                offset++;

                String nickname = nicknames.get(entry.member());
                if (nickname != null) {
                    result.add(new UniversityDetailResponse.TopContributor(
                            rank, entry.member(), nickname, (long) entry.score()
                    ));
                    if (result.size() == 3) {
                        break;
                    }
                }
            }

            if (result.size() == 3 || entries.size() < 3) {
                break;
            }
            // 다음 구간은 현재까지 확인한 Redis 원소 다음부터 조회한다.
            entries = universityRankingService.getContributorsRange(universityId, offset, 3);
        }
        return result;
    }

    public boolean isMyUniversity(
            Long universityId,
            Long myUniversityId
    ) {
        return myUniversityId != null
                && myUniversityId.equals(universityId);
    }

    public UniversityDetailResponse getUniversityDetail(
            Long universityId,
            Long myUniversityId
    ) {
        // 1. PostgreSQL에서 학교 기본 정보 조회
        University university = findUniversity(universityId);

        // 2. Redis에서 학교 점수와 순위 조회
        // 학교는 위에서 검증했으므로 중복 findById 호출 없이 Redis만 조회한다.
        Optional<UniversityScoreRank> scoreRank =
                readUniversityScoreRank(universityId);

        // 3. Redis 점수가 없으면 DB에 저장된 점수 사용
        Long totalScore = scoreRank
                .map(UniversityScoreRank::totalScore)
                .orElse(university.getTotalScore());
        // 숨김 학교(운영팀용)는 공개 순위가 없어 순위는 비우고, 점수는 숨김 학교 점수 키에서 읽는다 (SCRUM-238)
        if (university.isHidden()) {
            var hiddenScore = universityRankingService.getHiddenUniversityScore(universityId);
            if (hiddenScore.isPresent()) {
                totalScore = (long) hiddenScore.getAsDouble();
            }
        }

        // 4. Redis 순위가 없으면 null
        Long rank = scoreRank
                .map(UniversityScoreRank::rank)
                .orElse(null);

        // 5. Redis 상위 학과 + PostgreSQL 학과 이름
        List<UniversityDetailResponse.TopDepartment> topDepartments =
                getTopDepartmentsFromRedis(universityId);
        // Redis에 학과 순위가 없는 초기 상태에서는 DB의 점수순 상위 3개를 반환한다.
        if (topDepartments.isEmpty()) {
            topDepartments = getTopDepartments(universityId);
        }

        // 6. Redis 상위 기여자 + PostgreSQL 닉네임
        List<UniversityDetailResponse.TopContributor> topContributors =
                getTopContributorsFromRedis(universityId);

        // 7. Redis 1분 캐시를 이용한 회원 수 조회
        long memberCount =
                universityMemberService.getMemberCount(universityId);

        // 8. 조회한 학교가 내 학교인지 비교
        boolean myUniversity =
                isMyUniversity(universityId, myUniversityId);

        // 9. 같은 연합 학교면 방문 화면이 공격 대신 지원을 보인다 (SCRUM-187)
        boolean myAlly = allianceAllyChecker.areAllies(myUniversityId, universityId);

        // 10. 순위 화면과 같은 기준(00:00 KST 복사본)의 순위 변동 (SCRUM-189)
        Long rankChange = rank == null ? null : RankSnapshotService.change(
                rankSnapshotService.previousRanks(RedisKey.RANK_UNIV_SNAPSHOT.of(), List.of(universityId), List.of()),
                universityId, rank);

        // 11. 모든 정보를 하나의 DTO로 반환
        return new UniversityDetailResponse(
                university.getId(),
                university.getName(),
                university.getLogoUrl(),
                totalScore,
                rank,
                memberCount,
                topDepartments,
                topContributors,
                myUniversity,
                myAlly,
                rankChange
        );
    }
}

