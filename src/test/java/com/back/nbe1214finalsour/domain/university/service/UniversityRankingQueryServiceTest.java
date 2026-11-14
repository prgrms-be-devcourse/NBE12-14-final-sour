package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class UniversityRankingQueryServiceTest {

    @Mock
    private UniversityRankingService rankingService;

    @Mock
    private UniversityRepository universityRepository;

    @InjectMocks
    private UniversityRankingQueryService queryService;

    @Test
    @DisplayName("페이지가 바뀌어도 공동 순위를 유지한다")
    void getUniversities_tieAcrossPage() {
        University first = mock(University.class);
        University second = mock(University.class);

        given(first.getId()).willReturn(11L);
        given(first.getName()).willReturn("학교A");
        given(second.getId()).willReturn(22L);
        given(second.getName()).willReturn("학교B");

        given(rankingService.getUniversityCount())
                .willReturn(4L);

        given(rankingService.getUniversitiesRange(2, 2))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(11L, 1000.0),
                        new RedisHandler.RankEntry(22L, 500.0)
                ));

        given(universityRepository.findAllById(
                List.of(11L, 22L)
        )).willReturn(List.of(first, second));

        given(rankingService.getUniversityRank(11L))
                .willReturn(OptionalLong.of(1L));

        given(rankingService.getUniversityScore(11L))
                .willReturn(OptionalDouble.of(1000.0));

        var result = queryService.getUniversities(1, 2, 11L);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.items().get(1).rank()).isEqualTo(4L);
        assertThat(result.myUniversity().rank()).isEqualTo(1L);
        assertThat(result.hasNext()).isFalse();
    }
}