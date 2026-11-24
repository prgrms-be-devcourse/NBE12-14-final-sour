package com.back.nbe1214finalsour.domain.auth.dto;

import com.back.nbe1214finalsour.domain.university.entity.Department;
import io.swagger.v3.oas.annotations.media.Schema;

/** 응답에 넣는 학과 요약 ({@code {"id": 301, "name": "컴퓨터공학부"}}) */
public record DepartmentSummary(
        @Schema(description = "학과 ID", example = "301")
        Long id,
        @Schema(description = "학과 이름", example = "컴퓨터공학과")
        String name) {

    public static DepartmentSummary from(Department department) {
        return new DepartmentSummary(department.getId(), department.getName());
    }
}
