package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.dto.UserRankingResponse;
import com.back.nbe1214finalsour.domain.university.repository.UniversityContributorReader;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserRankingQueryService {

    private final UniversityRankingService rankingService;
    private final UniversityRepository universityRepository;
    private final UniversityContributorReader contributorReader;

    private record Excluded(long position, double score) {}

    @Transactional(readOnly = true)
    public UserRankingResponse getUsers(
            Long universityId,
            int page,
            int size,
            Long myUniversityId,
            Long myUserId
    ) {
        if (!universityRepository.existsById(universityId)) {
            throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        }

        long offset = (long) page * size;
        long rawCount = rankingService.getContributorCount(universityId);

        // Redis 기여도 ZSET은 구매/업그레이드 잔액으로도 사용되므로 ZREM하면 안 된다.
        // PostgreSQL에서 비공개 회원을 찾아 랭킹 계산에서만 제외한다.
        List<Excluded> excluded = new ArrayList<>();
        for (Long userId : contributorReader.findExcludedUserIds(universityId)) {
            var position = rankingService.getContributorPosition(universityId, userId);
            if (position.isEmpty()) {
                continue; // DB에는 있지만 아직 Redis 랭킹에 없는 회원
            }
            var score = rankingService.getContributorScore(universityId, userId);
            if (score.isPresent()) {
                excluded.add(new Excluded(position.getAsLong(), score.getAsDouble()));
            }
        }

        excluded.sort(Comparator.comparingLong(Excluded::position));
        long totalCount = rawCount - excluded.size();

        // 공개 순위 offset에 해당하는 원본 Redis 위치 계산 (비공개 회원만 건너뛴다).
        long rawStart = offset;
        for (Excluded member : excluded) {
            if (member.position() > rawStart) {
                break;
            }
            rawStart++;
        }

        // 페이지에 필요한 만큼만 가져오도록, 중간의 비공개 회원 수만큼만 범위를 넓힌다.
        long fetchCount = rawStart < rawCount ? Math.min(rawCount - rawStart, size) : 0;
        for (Excluded member : excluded) {
            if (member.position() < rawStart) {
                continue;
            }
            if (member.position() >= rawStart + fetchCount) {
                break;
            }
            fetchCount = Math.min(rawCount - rawStart, fetchCount + 1);
        }

        List<RedisHandler.RankEntry> entries = fetchCount == 0
                ? List.of()
                : rankingService.getContributorsRange(universityId, rawStart, fetchCount);

        List<Long> userIds = new ArrayList<>(entries.stream()
                .map(RedisHandler.RankEntry::member).toList());

        boolean isMyUniversity = myUniversityId != null && myUniversityId.equals(universityId);
        if (isMyUniversity && myUserId != null && !userIds.contains(myUserId)) {
            userIds.add(myUserId);
        }

        Map<Long, UniversityContributorReader.RankingProfile> profiles =
                contributorReader.findRankingProfiles(universityId, userIds);

        List<UserRankingResponse.Item> items = new ArrayList<>();
        List<Double> excludedScores = excluded.stream()
                .map(Excluded::score)
                .sorted(Comparator.reverseOrder())
                .toList();

        for (RedisHandler.RankEntry entry : entries) {
            var profile = profiles.get(entry.member());
            if (profile == null) {
                continue;
            }
            if (items.size() >= size) {
                break;
            }

            long originalRank = rankingService.getContributorRank(universityId, entry.member())
                    .orElseThrow(() -> new IllegalStateException("Redis 랭킹 정보가 조회 중 변경되었습니다."));
            long publicRank = originalRank - countExcludedHigher(excludedScores, entry.score());

            items.add(new UserRankingResponse.Item(
                    publicRank,
                    profile.userId(),
                    profile.nickname(),
                    profile.profileImageUrl(),
                    new UserRankingResponse.DepartmentInfo(profile.departmentId(), profile.departmentName()),
                    (long) entry.score()
            ));
        }

        UserRankingResponse.Me me = null;
        if (isMyUniversity && myUserId != null && profiles.containsKey(myUserId)) {
            var myRank = rankingService.getContributorRank(universityId, myUserId);
            var myScore = rankingService.getContributorScore(universityId, myUserId);
            if (myRank.isPresent() && myScore.isPresent()) {
                me = new UserRankingResponse.Me(
                        myRank.getAsLong() - countExcludedHigher(excludedScores, myScore.getAsDouble()),
                        (long) myScore.getAsDouble()
                );
            }
        }

        boolean hasNext = offset + size < totalCount;
        return new UserRankingResponse(items, page, size, totalCount, hasNext, me);
    }

    // 내 점수보다 높은 비공개 회원 수 (동점자는 뺄 필요가 없다)
    private static long countExcludedHigher(List<Double> descendingScores, double targetScore) {
        int left = 0;
        int right = descendingScores.size();
        while (left < right) {
            int mid = (left + right) >>> 1;
            if (Double.compare(descendingScores.get(mid), targetScore) > 0) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }
        return left;
    }
}