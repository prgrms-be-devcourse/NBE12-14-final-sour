package com.back.nbe1214finalsour.domain.university.entity;

import com.back.nbe1214finalsour.domain.university.entity.University;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UniversityDepartmentUnitTest {

    @Test
    @DisplayName("학교를 생성하면 이름이 설정되고 점수는 0이다")
    void createUniversity() {
        University university = new University("검증대학교");

        assertThat(university.getName()).isEqualTo("검증대학교");
        assertThat(university.getTotalScore()).isZero();
    }

    @Test
    @DisplayName("학교 이름이 비어 있으면 예외가 발생한다")
    void universityNameCannotBeBlank() {
        assertThatThrownBy(() -> new University(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("학교 이름은 비어 있을 수 없습니다.");
    }

    @Test
    @DisplayName("학과를 생성하면 학교가 연결되고 점수는 0이다")
    void createDepartment() {
        University university = new University("검증대학교");
        Department department = new Department(university, "컴퓨터공학과");

        assertThat(department.getUniversity()).isSameAs(university);
        assertThat(department.getName()).isEqualTo("컴퓨터공학과");
        assertThat(department.getTotalScore()).isZero();
    }

    @Test
    @DisplayName("소속 학교가 없으면 학과를 생성할 수 없다")
    void departmentRequiresUniversity() {
        assertThatThrownBy(() ->
                new Department(null, "컴퓨터공학과"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("소속 학교는 필수입니다.");
    }

    @Test
    @DisplayName("학과 이름이 비어 있으면 예외가 발생한다")
    void departmentNameCannotBeBlank() {
        University university = new University("검증대학교");

        assertThatThrownBy(() ->
                new Department(university, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("학과 이름은 비어 있을 수 없습니다.");
    }
}
