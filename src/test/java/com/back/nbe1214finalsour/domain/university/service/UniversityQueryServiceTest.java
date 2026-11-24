
package com.back.nbe1214finalsour.domain.university.service;

import com.back.nbe1214finalsour.domain.alliance.service.AllianceAllyChecker;
import com.back.nbe1214finalsour.domain.university.repository.UniversityContributorReader;
import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityDetailResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisHandler;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UniversityQueryServiceTest {

    @Mock
    private UniversityRepository universityRepository;

    @InjectMocks
    private UniversityQueryService universityQueryService;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private UniversityRankingService universityRankingService;

    @Mock
    private UniversityContributorReader universityContributorReader;

    @Mock
    private UniversityMemberService universityMemberService;

    @Mock
    private AllianceAllyChecker allianceAllyChecker;

    @Mock
    private RankSnapshotService rankSnapshotService;

    @Test
    @DisplayName("학교 이름으로 검색하면 DTO 목록을 반환한다")
    void searchUniversities() {

        University university = mock(University.class);

        given(university.getId()).willReturn(12L);
        given(university.getName()).willReturn("서울대학교");

        given(universityRepository.findByHiddenFalseAndNameContainingIgnoreCaseOrderByNameAsc(
                eq("서울"), any(Pageable.class)
        )).willReturn(List.of(university));

        List<UniversitySearchResponse> result =
                universityQueryService.searchUniversities("서울", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(12L);
        assertThat(result.get(0).name()).isEqualTo("서울대학교");
        assertThat(result.get(0).logoUrl()).isNull();

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(universityRepository)
                .findByHiddenFalseAndNameContainingIgnoreCaseOrderByNameAsc(
                        eq("서울"), captor.capture()
                );

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 목록을 반환한다")
    void searchUniversities_empty() {

        given(universityRepository.findByHiddenFalseAndNameContainingIgnoreCaseOrderByNameAsc(
                eq("없는학교"), any(Pageable.class)
        )).willReturn(List.of());

        List<UniversitySearchResponse> result =
                universityQueryService.searchUniversities("없는학교", 20);

        assertThat(result).isEmpty();
    }


    @Test
    @DisplayName("학교 ID로 학과 목록을 조회하면 DTO 목록을 반환한다")
    void getDepartments() {

        Long universityId = 12L;

        Department first = mock(Department.class);
        Department second = mock(Department.class);

        given(first.getId()).willReturn(301L);
        given(first.getName()).willReturn("경영학과");

        given(second.getId()).willReturn(302L);
        given(second.getName()).willReturn("컴퓨터공학과");

        given(universityRepository.existsById(universityId))
                .willReturn(true);
        given(departmentRepository.findByUniversity_IdOrderByNameAsc(universityId))
                .willReturn(List.of(first, second));

        List<DepartmentResponse> result =
                universityQueryService.getDepartments(universityId);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(DepartmentResponse::name)
                .containsExactly("경영학과", "컴퓨터공학과");

        verify(universityRepository).existsById(universityId);
        verify(departmentRepository)
                .findByUniversity_IdOrderByNameAsc(universityId);
    }


    @Test
    @DisplayName("학교는 존재하지만 학과가 없으면 빈 목록 반환")
    void getDepartments_empty(){

        Long universityId = 12L;

        given(universityRepository.existsById(universityId))
                .willReturn(true);

        given(departmentRepository.findByUniversity_IdOrderByNameAsc(universityId))
                .willReturn(List.of());

        List<DepartmentResponse> result =
                universityQueryService.getDepartments(universityId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 학교의 학과를 조회하면 예외가 발생한다")
    void getDepartments_universityNotFound() {

        Long universityId = 999999L;

        given(universityRepository.existsById(universityId))
                .willReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() ->
                        universityQueryService.getDepartments(universityId)
                )
                .satisfies(exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNIV_NOT_FOUND)
                );

        verify(universityRepository).existsById(universityId);
    }

    @Test
    @DisplayName("학교 ID로 조회하면 해당 학교를 반환한다")
    void findUniversity() {

        Long universityId = 12L;
        University university = mock(University.class);

        given(universityRepository.findById(universityId))
                .willReturn(Optional.of(university));

        University result = universityQueryService.findUniversity(universityId);

        assertThat(result).isSameAs(university);
        verify(universityRepository).findById(universityId);
    }

    @Test
    @DisplayName("존재하지 않는 학교 ID면 UNIV_NOT_FOUND 예외가 발생한다")
    void findUniversity_notFound() {

        Long universityId = 999999L;

        given(universityRepository.findById(universityId))
                .willReturn(Optional.empty());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() ->
                        universityQueryService.findUniversity(universityId)
                )
                .satisfies(exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNIV_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("학교별 점수 상위 학과 3개를 DTO로 반환한다")
    void getTopDepartments() {

        Long universityId = 12L;

        Department first = mock(Department.class);
        Department second = mock(Department.class);

        given(first.getId()).willReturn(301L);
        given(first.getName()).willReturn("경영학과");
        given(first.getTotalScore()).willReturn(5000L);

        given(second.getId()).willReturn(302L);
        given(second.getName()).willReturn("컴퓨터공학과");
        given(second.getTotalScore()).willReturn(4000L);

        given(departmentRepository.findByUniversity_IdOrderByTotalScoreDescIdAsc(
                eq(universityId),
                any(Pageable.class)
        )).willReturn(List.of(first, second));

        List<UniversityDetailResponse.TopDepartment> result =
                universityQueryService.getTopDepartments(universityId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).rank()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("경영학과");
        assertThat(result.get(1).rank()).isEqualTo(2L);
        assertThat(result.get(1).name()).isEqualTo("컴퓨터공학과");
    }

    @Test
    @DisplayName("학교가 존재하면 Redis 점수와 순위를 함께 조회한다")
    void getUniversityScoreRank() {

        Long universityId = 12L;
        University university = mock(University.class);

        given(universityRepository.findById(universityId))
                .willReturn(Optional.of(university));

        given(universityRankingService.getUniversityScore(universityId))
                .willReturn(OptionalDouble.of(15000.0));

        given(universityRankingService.getUniversityRank(universityId))
                .willReturn(OptionalLong.of(3L));

        var result = universityQueryService.getUniversityScoreRank(universityId);

        assertThat(result).isPresent();
        assertThat(result.get().totalScore()).isEqualTo(15000L);
        assertThat(result.get().rank()).isEqualTo(3L);

        verify(universityRepository).findById(universityId);
        verify(universityRankingService).getUniversityScore(universityId);
        verify(universityRankingService).getUniversityRank(universityId);
    }

    @Test
    @DisplayName("Redis에 학교 점수가 없으면 빈 결과를 반환한다")
    void getUniversityScoreRank_noRedisScore() {

        Long universityId = 12L;
        University university = mock(University.class);

        given(universityRepository.findById(universityId))
                .willReturn(Optional.of(university));

        given(universityRankingService.getUniversityScore(universityId))
                .willReturn(OptionalDouble.empty());

        var result = universityQueryService.getUniversityScoreRank(universityId);

        assertThat(result).isEmpty();

        verify(universityRankingService, never())
                .getUniversityRank(universityId);
    }

    @Test
    @DisplayName("Redis 상위 학과 목록을 DB 학과 정보와 합쳐 반환한다")
    void getTopDepartmentsFromRedis() {

        Long universityId = 12L;

        Department first = mock(Department.class);
        Department second = mock(Department.class);

        given(first.getId()).willReturn(301L);
        given(first.getName()).willReturn("경영학과");

        given(second.getId()).willReturn(302L);
        given(second.getName()).willReturn("컴퓨터공학과");

        given(universityRankingService.getTopDepartments(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(301L, 5000.0),
                        new RedisHandler.RankEntry(302L, 4000.0)
                ));

        given(departmentRepository.findByUniversity_IdAndIdIn(
                eq(universityId),
                any()
        )).willReturn(List.of(first, second));

        List<UniversityDetailResponse.TopDepartment> result =
                universityQueryService.getTopDepartmentsFromRedis(universityId);

        assertThat(result).hasSize(2);

        assertThat(result.get(0).rank()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("경영학과");
        assertThat(result.get(0).totalScore()).isEqualTo(5000L);

        assertThat(result.get(1).rank()).isEqualTo(2L);
        assertThat(result.get(1).name()).isEqualTo("컴퓨터공학과");
        assertThat(result.get(1).totalScore()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("Redis 상위 기여자와 DB 닉네임을 합쳐 반환한다")
    void getTopContributorsFromRedis() {

        Long universityId = 12L;

        given(universityRankingService.getTopContributors(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 9000.0),
                        new RedisHandler.RankEntry(102L, 7000.0),
                        new RedisHandler.RankEntry(103L, 7000.0)
                ));

        given(universityContributorReader.findNicknames(
                universityId, List.of(101L, 102L, 103L)
        )).willReturn(Map.of(
                101L, "클릭장인",
                102L, "대대대왕",
                103L, "점수왕"
        ));

        List<UniversityDetailResponse.TopContributor> result =
                universityQueryService.getTopContributorsFromRedis(universityId);

        assertThat(result).hasSize(3);

        assertThat(result)
                .extracting(UniversityDetailResponse.TopContributor::nickname)
                .containsExactly("클릭장인", "대대대왕", "점수왕");

        assertThat(result)
                .extracting(UniversityDetailResponse.TopContributor::rank)
                .containsExactly(1L, 2L, 2L);

        assertThat(result.get(0).contribScore()).isEqualTo(9000L);
        assertThat(result.get(1).contribScore()).isEqualTo(7000L);
    }


    @Test
    @DisplayName("조회한 학교가 내 학교라면 true를 반환한다")
    void isMyUniversity_same() {

        Long universityId = 12L;
        Long myUniversityId = 12L;

        boolean result = universityQueryService.isMyUniversity(
                universityId, myUniversityId
        );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("조회한 학교가 내 학교가 아니면 false를 반환한다")
    void isMyUniversity_different() {

        Long universityId = 12L;
        Long myUniversityId = 9L;

        boolean result = universityQueryService.isMyUniversity(
                universityId, myUniversityId
        );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("비로그인 사용자는 false를 반환한다")
    void isMyUniversity_guest() {

        Long universityId = 12L;
        Long myUniversityId = null;

        boolean result = universityQueryService.isMyUniversity(
                universityId, myUniversityId
        );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("학교 상세 조회 시 학교 정보와 Redis 점수, 회원 수를 반환한다")
    void getUniversityDetail() {

        Long universityId = 12L;
        Long myUniversityId = 12L;

        University university = mock(University.class);

        given(university.getId()).willReturn(12L);
        given(university.getName()).willReturn("서울대학교");
        given(university.getTotalScore()).willReturn(10000L);

        given(universityRepository.findById(universityId))
                .willReturn(Optional.of(university));

        given(universityRankingService.getUniversityScore(universityId))
                .willReturn(OptionalDouble.of(15000.0));

        given(universityRankingService.getUniversityRank(universityId))
                .willReturn(OptionalLong.of(3L));

        given(universityMemberService.getMemberCount(universityId))
                .willReturn(498L);

        // 순위 화면과 같은 기준: 00:00 KST 복사본에서 5위였다 (SCRUM-189)
        given(rankSnapshotService.previousRanks(
                RedisKey.RANK_UNIV_SNAPSHOT.of(), List.of(universityId), List.of()
        )).willReturn(Map.of(universityId, 5L));

        UniversityDetailResponse result =
                universityQueryService.getUniversityDetail(
                        universityId, myUniversityId
                );

        assertThat(result.id()).isEqualTo(12L);
        assertThat(result.name()).isEqualTo("서울대학교");

        assertThat(result.totalScore()).isEqualTo(15000L);
        assertThat(result.rank()).isEqualTo(3L);
        assertThat(result.rankChange()).isEqualTo(2L);

        assertThat(result.memberCount()).isEqualTo(498L);
        assertThat(result.isMyUniversity()).isTrue();

        assertThat(result.topDepartments()).isEmpty();
        assertThat(result.topContributors()).isEmpty();

        verify(universityMemberService).getMemberCount(universityId);
    }


    @Test
    @DisplayName("Redis 기여자에 탈퇴 또는 삭제된 사용자가 남아 있어도 상세 조회가 실패하지 않는다")
    void getTopContributorsFromRedis_missingNickname() {
        Long universityId = 12L;
        given(universityRankingService.getTopContributors(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 9000.0),
                        new RedisHandler.RankEntry(102L, 7000.0),
                        new RedisHandler.RankEntry(103L, 5000.0)
                ));
        given(universityContributorReader.findNicknames(
                universityId, List.of(101L, 102L, 103L)
        )).willReturn(Map.of(101L, "클릭장인", 103L, "점수왕"));

        var result = universityQueryService.getTopContributorsFromRedis(universityId);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(UniversityDetailResponse.TopContributor::userId)
                .containsExactly(101L, 103L);
        // 실제 Redis의 1, 2, 3위 중 2위가 비노출이므로 원래 순위를 유지한다.
        assertThat(result).extracting(UniversityDetailResponse.TopContributor::rank)
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("Redis 학과 순위가 없으면 DB에 저장된 학과 점수로 상위 학과를 구성한다")
    void getUniversityDetail_departmentFallback() {
        Long universityId = 12L;
        University university = mock(University.class);
        given(university.getId()).willReturn(universityId);
        given(university.getTotalScore()).willReturn(0L);
        given(universityRepository.findById(universityId))
                .willReturn(Optional.of(university));

        Department department = mock(Department.class);
        given(department.getId()).willReturn(301L);
        given(department.getName()).willReturn("컴퓨터공학과");
        given(department.getTotalScore()).willReturn(300L);
        given(departmentRepository.findByUniversity_IdOrderByTotalScoreDescIdAsc(
                eq(universityId), any(Pageable.class)
        )).willReturn(List.of(department));

        var result = universityQueryService.getUniversityDetail(universityId, null);
        assertThat(result.totalScore()).isZero();
        assertThat(result.rank()).isNull();
        assertThat(result.rankChange()).isNull();
        verify(rankSnapshotService, never()).previousRanks(any(), any(), any());
        assertThat(result.topDepartments()).hasSize(1);
        assertThat(result.topDepartments().getFirst().totalScore()).isEqualTo(300L);
        verify(universityRepository).findById(universityId);
    }
    @Test
    @DisplayName("Redis 학과 ID가 DB에 없으면 DB 기준 상위 학과로 대체한다")
    void getTopDepartmentsFromRedis_staleDepartmentFallback() {
        Long universityId = 12L;
        given(universityRankingService.getTopDepartments(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(999L, 9000.0),
                        new RedisHandler.RankEntry(301L, 5000.0)
                ));

        Department existingDepartment = mock(Department.class);
        given(existingDepartment.getId()).willReturn(301L);
        given(departmentRepository.findByUniversity_IdAndIdIn(
                eq(universityId), eq(List.of(999L, 301L))
        )).willReturn(List.of(existingDepartment));

        Department fallbackDepartment = mock(Department.class);
        given(fallbackDepartment.getId()).willReturn(302L);
        given(fallbackDepartment.getName()).willReturn("컴퓨터공학과");
        given(fallbackDepartment.getTotalScore()).willReturn(4000L);
        given(departmentRepository.findByUniversity_IdOrderByTotalScoreDescIdAsc(
                eq(universityId), any(Pageable.class)
        )).willReturn(List.of(fallbackDepartment));

        var result = universityQueryService.getTopDepartmentsFromRedis(universityId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(302L);
        assertThat(result.getFirst().rank()).isEqualTo(1L);
        assertThat(result.getFirst().totalScore()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("탈퇴·정지 회원을 건너뛰고 다음 Redis 순위에서 최대 3명을 보충한다")
    void getTopContributorsFromRedis_fillAfterExcludedUsers() {
        Long universityId = 12L;
        given(universityRankingService.getTopContributors(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 9000.0),
                        new RedisHandler.RankEntry(102L, 8000.0),
                        new RedisHandler.RankEntry(103L, 7000.0)
                ));
        given(universityContributorReader.findNicknames(
                universityId, List.of(101L, 102L, 103L)
        )).willReturn(Map.of(101L, "A"));
        given(universityRankingService.getContributorsRange(universityId, 3, 3))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(104L, 7000.0),
                        new RedisHandler.RankEntry(105L, 6000.0)
                ));
        given(universityContributorReader.findNicknames(
                universityId, List.of(104L, 105L)
        )).willReturn(Map.of(104L, "D", 105L, "E"));

        var result = universityQueryService.getTopContributorsFromRedis(universityId);

        assertThat(result).extracting(UniversityDetailResponse.TopContributor::userId)
                .containsExactly(101L, 104L, 105L);
        // Redis 원래 순위: 1위, 공동 3위, 5위
        assertThat(result).extracting(UniversityDetailResponse.TopContributor::rank)
                .containsExactly(1L, 3L, 5L);
        verify(universityRankingService).getContributorsRange(universityId, 3, 3);
    }

    @Test
    @DisplayName("유효 기여자가 3명이면 Redis 다음 구간을 조회하지 않는다")
    void getTopContributorsFromRedis_noExtraReadWhenFull() {
        Long universityId = 12L;
        given(universityRankingService.getTopContributors(universityId))
                .willReturn(List.of(
                        new RedisHandler.RankEntry(101L, 9000.0),
                        new RedisHandler.RankEntry(102L, 8000.0),
                        new RedisHandler.RankEntry(103L, 7000.0)
                ));
        given(universityContributorReader.findNicknames(
                universityId, List.of(101L, 102L, 103L)
        )).willReturn(Map.of(101L, "A", 102L, "B", 103L, "C"));

        assertThat(universityQueryService.getTopContributorsFromRedis(universityId))
                .hasSize(3);
        verify(universityRankingService, never())
                .getContributorsRange(eq(universityId), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

}
