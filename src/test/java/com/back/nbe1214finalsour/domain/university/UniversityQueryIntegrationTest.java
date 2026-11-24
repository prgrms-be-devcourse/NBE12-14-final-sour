package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.university.service.UniversityQueryService;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@Transactional
class UniversityQueryIntegrationTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UniversityQueryService universityQueryService;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("학교 이름 일부로 검색하면 일치하는 학교만 조회된다")
    void searchUniversities_byKeyword() {

        universityRepository.save(new University("검증A대학교"));
        universityRepository.save(new University("검증B대학교"));
        universityRepository.save(new University("검정C대학교"));

        universityRepository.flush();
        entityManager.clear();

        List<UniversitySearchResponse> result =
                universityQueryService.searchUniversities("검증", 20);

        assertThat(result).hasSize(2);

        assertThat(result)
                .extracting(UniversitySearchResponse::name)
                .containsExactly(
                        "검증A대학교",
                        "검증B대학교"
                );
    }

    @Test
    @DisplayName("학교 검색 결과는 이름순으로 정렬하고 size만큼 조회한다")
    void searchUniversities_orderAndLimit() {

        universityRepository.save(new University("검증C대학교"));
        universityRepository.save(new University("검증A대학교"));
        universityRepository.save(new University("검증B대학교"));

        universityRepository.flush();
        entityManager.clear();

        List<UniversitySearchResponse> result =
                universityQueryService.searchUniversities("검증", 2);

        assertThat(result).hasSize(2);

        assertThat(result)
                .extracting(UniversitySearchResponse::name)
                .containsExactly(
                        "검증A대학교",
                        "검증B대학교"
                );
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 목록을 반환한다")
    void searchUniversities_notFound() {

        List<UniversitySearchResponse> result =
                universityQueryService.searchUniversities(
                        "노검증대학교",
                        20
                );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("해당 학교의 학과만 이름순으로 조회한다")
    void getDepartments_byUniversity() {

        University firstUniversity = universityRepository.save(new University("검증A대학교"));
        University secondUniversity = universityRepository.save(new University("검증B대학교"));

        departmentRepository.save(new Department(firstUniversity, "컴퓨터공학과"));
        departmentRepository.save(new Department(firstUniversity, "경영학과"));
        departmentRepository.save(new Department(secondUniversity, "전자공학과"));

        departmentRepository.flush();
        entityManager.clear();

        List<DepartmentResponse> result =
                universityQueryService.getDepartments(firstUniversity.getId());

        assertThat(result).hasSize(2);

        assertThat(result)
                .extracting(DepartmentResponse::name)
                .containsExactly("경영학과", "컴퓨터공학과");
    }

    @Test
    @DisplayName("존재하지 않는 학교의 학과를 조회하면 UNIV_NOT_FOUND 예외가 발생한다")
    void getDepartments_universityNotFound() {

        Long universityId = -1L;

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() ->
                        universityQueryService.getDepartments(universityId)
                )
                .satisfies(exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.UNIV_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("학교별 점수 상위 학과 3개를 조회한다")
    void findTopDepartments() {

        University university = universityRepository.save(new University("검증대학교"));

        Department computer = departmentRepository.save(new Department(university, "컴퓨터공학과"));
        Department business = departmentRepository.save(new Department(university, "경영학과"));
        Department design = departmentRepository.save(new Department(university, "전자공학과"));
        Department english = departmentRepository.save(new Department(university, "일어일문학과"));

        // 테스트용 누적 점수 설정
        jdbcTemplate.update(
                "UPDATE department SET total_score = ? WHERE id = ?",
                3000L, computer.getId()
        );
        jdbcTemplate.update(
                "UPDATE department SET total_score = ? WHERE id = ?",
                5000L, business.getId()
        );
        jdbcTemplate.update(
                "UPDATE department SET total_score = ? WHERE id = ?",
                4000L, design.getId()
        );
        jdbcTemplate.update(
                "UPDATE department SET total_score = ? WHERE id = ?",
                2000L, english.getId()
        );

        List<Department> result =
                departmentRepository
                        .findByUniversity_IdOrderByTotalScoreDescIdAsc(
                                university.getId(),
                                PageRequest.of(0, 3)
                        );

        assertThat(result).hasSize(3);

        assertThat(result)
                .extracting(Department::getName)
                .containsExactly(
                        "경영학과",
                        "전자공학과",
                        "컴퓨터공학과"
                );
    }
}
