package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.repository.UniversityContributorReader;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserRankingQueryServiceTest {

    @Mock
    private UniversityRankingService rankingService;

    @Mock
    private UniversityRepository universityRepository;

    @Mock
    private UniversityContributorReader contributorReader;

    @InjectMocks
    private UserRankingQueryService queryService;

    @Test
    @DisplayName("페이지 경계에서도 공동 순위를 유지한다")
    void getUsers_tieAcrossPage() {
        given(universityRepository.existsById(12L))
                .willReturn(true);

        given(rankingService.getContributorCount(12L))
                .willReturn(4L);

        given(rankingService.getContributorsRange(12L, 2, 2))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 1000.0),
                        new RedisHandler.RankEntry(102L, 500.0)
                ));

        given(contributorReader.findRankingProfiles(
                12L, List.of(101L, 102L)
        )).willReturn(Map.of(
                101L, new UniversityContributorReader.RankingProfile(
                        101L, "클릭왕", null, 301L, "컴퓨터공학과"
                ),
                102L, new UniversityContributorReader.RankingProfile(
                        102L, "클릭장인", null, 302L, "디자인학과"
                )
        ));

        given(rankingService.getContributorRank(12L, 101L))
                .willReturn(OptionalLong.of(1L));
        given(rankingService.getContributorRank(12L, 102L))
                .willReturn(OptionalLong.of(4L));

        var result = queryService.getUsers(
                12L, 1, 2, null, null
        );

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.items().get(1).rank()).isEqualTo(4L);
        assertThat(result.items().get(0).nickname()).isEqualTo("클릭왕");
        assertThat(result.items().get(0).department().name())
                .isEqualTo("컴퓨터공학과");
        assertThat(result.hasNext()).isFalse();
        assertThat(result.me()).isNull();
    }

    @Test
    @DisplayName("탈퇴 회원이 1위여도 첫 페이지에는 정상 회원 1위만 표시한다")
    void getUsers_inactiveExcluded() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(rankingService.getContributorCount(12L)).willReturn(2L);
        given(contributorReader.findExcludedUserIds(12L)).willReturn(List.of(101L));
        given(rankingService.getContributorPosition(12L, 101L))
                .willReturn(OptionalLong.of(0L));
        given(rankingService.getContributorScore(12L, 101L))
                .willReturn(OptionalDouble.of(9000.0));

        given(rankingService.getContributorsRange(12L, 1, 1))
                .willReturn(List.of(new RedisHandler.RankEntry(102L, 7000.0)));
        given(contributorReader.findRankingProfiles(12L, List.of(102L)))
                .willReturn(Map.of(102L, new UniversityContributorReader.RankingProfile(
                        102L, "활성회원", null, 301L, "디자인학과")));
        given(rankingService.getContributorRank(12L, 102L))
                .willReturn(OptionalLong.of(2L));

        var result = queryService.getUsers(12L, 0, 1, null, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).userId()).isEqualTo(102L);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.totalCount()).isEqualTo(1L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("동점인 탈퇴 회원을 제외해도 남은 정상 회원의 공동 순위를 유지한다")
    void getUsers_sameScoreExcluded() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(rankingService.getContributorCount(12L)).willReturn(3L);
        given(contributorReader.findExcludedUserIds(12L)).willReturn(List.of(102L));
        given(rankingService.getContributorPosition(12L, 102L))
                .willReturn(OptionalLong.of(1L));
        given(rankingService.getContributorScore(12L, 102L))
                .willReturn(OptionalDouble.of(5000.0));
        given(rankingService.getContributorsRange(12L, 0, 3))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 5000.0),
                        new RedisHandler.RankEntry(102L, 5000.0),
                        new RedisHandler.RankEntry(103L, 5000.0)
                ));
        given(contributorReader.findRankingProfiles(12L, List.of(101L, 102L, 103L)))
                .willReturn(Map.of(
                        101L, new UniversityContributorReader.RankingProfile(101L, "정상A", null, 301L, "학과"),
                        103L, new UniversityContributorReader.RankingProfile(103L, "정상B", null, 301L, "학과")
                ));
        given(rankingService.getContributorRank(12L, 101L)).willReturn(OptionalLong.of(1L));
        given(rankingService.getContributorRank(12L, 103L)).willReturn(OptionalLong.of(1L));

        var result = queryService.getUsers(12L, 0, 2, null, null);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.items().get(1).rank()).isEqualTo(1L);
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("현재 페이지 밖의 내 순위도 비공개 회원을 제외하여 계산한다")
    void getUsers_myPublicRank() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(rankingService.getContributorCount(12L)).willReturn(3L);
        given(contributorReader.findExcludedUserIds(12L)).willReturn(List.of(101L));
        given(rankingService.getContributorPosition(12L, 101L))
                .willReturn(OptionalLong.of(0L));
        given(rankingService.getContributorScore(12L, 101L))
                .willReturn(OptionalDouble.of(9000.0));

        given(rankingService.getContributorsRange(12L, 1, 1))
                .willReturn(List.of(new RedisHandler.RankEntry(102L, 7000.0)));
        given(contributorReader.findRankingProfiles(12L, List.of(102L, 103L)))
                .willReturn(Map.of(
                        102L, new UniversityContributorReader.RankingProfile(102L, "정상A", null, 301L, "학과"),
                        103L, new UniversityContributorReader.RankingProfile(103L, "정상B", null, 301L, "학과")
                ));
        given(rankingService.getContributorRank(12L, 102L)).willReturn(OptionalLong.of(2L));
        given(rankingService.getContributorRank(12L, 103L)).willReturn(OptionalLong.of(3L));
        given(rankingService.getContributorScore(12L, 103L))
                .willReturn(OptionalDouble.of(5000.0));

        var result = queryService.getUsers(12L, 0, 1, 12L, 103L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.totalCount()).isEqualTo(2L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.me().rank()).isEqualTo(2L);
        assertThat(result.me().contribScore()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("존재하지 않는 학교는 예외를 반환한다")
    void getUsers_universityNotFound() {
        given(universityRepository.existsById(999L))
                .willReturn(false);

        assertThatThrownBy(() ->
                queryService.getUsers(
                        999L, 0, 20, null, null
                )
        ).isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode())
                                .isEqualTo(ErrorCode.UNIV_NOT_FOUND)
                );

        verifyNoInteractions(rankingService, contributorReader);
    }
}
