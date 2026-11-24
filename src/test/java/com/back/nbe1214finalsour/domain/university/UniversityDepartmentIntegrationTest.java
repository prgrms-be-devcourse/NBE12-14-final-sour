package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@IntegrationTest
@Transactional
class UniversityDepartmentIntegrationTest {

    @Autowired
    private UniversityRepository universityRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("학교와 학과를 저장하고 조회할 수 있다")
    void saveAndFindUniversityDepartment() {

        University university = universityRepository
                .saveAndFlush(new University("검증대학교"));

        Long universityId = university.getId();

        departmentRepository.save(new Department(university, "컴퓨터공학과"));
        departmentRepository.save(new Department(university, "경영학과"));

        departmentRepository.flush();
        entityManager.clear();

        University foundUniversity = universityRepository
                .findByName("검증대학교")
                .orElseThrow();

        List<Department> departments = departmentRepository
                .findByUniversity_IdOrderByNameAsc(universityId);

        assertThat(foundUniversity.getName())
                .isEqualTo("검증대학교");

        assertThat(departments).hasSize(2);

        assertThat(departments)
                .extracting(Department::getName)
                .containsExactly("경영학과", "컴퓨터공학과");

        assertThat(universityRepository.existsByName("검증대학교"))
                .isTrue();

        assertThat(departmentRepository.existsByUniversity_IdAndName(
                universityId, "컴퓨터공학과"))
                .isTrue();
    }

    @Test
    @DisplayName("같은 이름의 학교를 중복 등록할 수 없다")
    void duplicateUniversityName() {

        universityRepository.saveAndFlush(
                new University("검증대학교")
        );

        assertThatThrownBy(() ->
                universityRepository.saveAndFlush(
                        new University("검증대학교")
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 학교에 동일한 이름의 학과를 중복 등록할 수 없다")
    void duplicateDepartmentName() {

        University university = universityRepository.saveAndFlush(
                new University("검증대학교")
        );

        departmentRepository.saveAndFlush(
                new Department(university, "컴퓨터공학과")
        );

        assertThatThrownBy(() ->
                departmentRepository.saveAndFlush(
                        new Department(university, "컴퓨터공학과")
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("생성·수정 시각이 저장할 때 채워진다")
    void auditingFieldsArePopulated() {
        University university = universityRepository.saveAndFlush(
                new University("검증대학교")
        );
        Department department = departmentRepository.saveAndFlush(
                new Department(university, "검증학과")
        );

        assertThat(university.getCreatedAt()).isNotNull();
        assertThat(university.getUpdatedAt()).isNotNull();
        assertThat(department.getCreatedAt()).isNotNull();
        assertThat(department.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("다른 학교에는 같은 이름의 학과를 등록할 수 있다")
    void sameDepartmentNameInDifferentUniversities() {
        University first = universityRepository.saveAndFlush(
                new University("검증A대학교")
        );
        University second = universityRepository.saveAndFlush(
                new University("검증B대학교")
        );

        departmentRepository.saveAndFlush(new Department(first, "컴퓨터공학과"));
        departmentRepository.saveAndFlush(new Department(second, "컴퓨터공학과"));

        assertThat(departmentRepository.existsByUniversity_IdAndName(
                first.getId(), "컴퓨터공학과")).isTrue();
        assertThat(departmentRepository.existsByUniversity_IdAndName(
                second.getId(), "컴퓨터공학과")).isTrue();
    }

}
