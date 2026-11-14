package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DepartmentRankingQueryServiceTest {

    @Mock
    private UniversityRankingService rankingService;

    @Mock
    private UniversityRepository universityRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @InjectMocks
    private DepartmentRankingQueryService queryService;

    @Test
    @DisplayName("페이지 경계에서도 공동 순위를 유지한다")
    void getDepartments_tieAcrossPage() {
        Department first = mock(Department.class);
        Department second = mock(Department.class);

        given(first.getId()).willReturn(301L);
        given(first.getName()).willReturn("컴퓨터공학과");
        given(second.getId()).willReturn(302L);
        given(second.getName()).willReturn("전자공학과");

        given(universityRepository.existsById(12L))
                .willReturn(true);

        given(rankingService.getDepartmentCount(12L))
                .willReturn(4L);

        given(rankingService.getDepartmentsRange(12L, 2, 2))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(301L, 1000.0),
                        new RedisHandler.RankEntry(302L, 500.0)
                ));

        given(departmentRepository.findByUniversity_IdAndIdIn(
                12L, List.of(301L, 302L)
        )).willReturn(List.of(first, second));

        given(rankingService.getDepartmentRank(12L, 301L))
                .willReturn(OptionalLong.of(1L));

        given(rankingService.getDepartmentScore(12L, 301L))
                .willReturn(OptionalDouble.of(1000.0));

        var result = queryService.getDepartments(
                12L, 1, 2, 12L, 301L
        );

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).rank()).isEqualTo(1L);
        assertThat(result.items().get(1).rank()).isEqualTo(4L);
        assertThat(result.myDepartment().rank()).isEqualTo(1L);
        assertThat(result.myDepartment().departmentId()).isEqualTo(301L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 학교는 조회할 수 없다")
    void getDepartments_universityNotFound() {
        given(universityRepository.existsById(999L))
                .willReturn(false);

        assertThatThrownBy(() ->
                queryService.getDepartments(
                        999L, 0, 20, null, null
                )
        ).isInstanceOf(BusinessException.class);

        verifyNoInteractions(rankingService, departmentRepository);
    }

    @Test
    @DisplayName("다른 학교의 학과를 조회하면 내 학과 정보는 제외한다")
    void getDepartments_otherUniversity() {
        given(universityRepository.existsById(12L))
                .willReturn(true);

        var result = queryService.getDepartments(
                12L, 0, 20, 99L, 301L
        );

        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.myDepartment()).isNull();
    }
}