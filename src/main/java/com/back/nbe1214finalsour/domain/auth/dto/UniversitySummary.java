package com.back.nbe1214finalsour.domain.auth.dto;

import com.back.nbe1214finalsour.domain.university.entity.University;
import io.swagger.v3.oas.annotations.media.Schema;

/** 응답에 넣는 학교 요약 ({@code {"id": 12, "name": "서울대학교"}}) */
public record UniversitySummary(
        @Schema(description = "학교 ID", example = "12")
        Long id,
        @Schema(description = "학교 이름", example = "예시대학교")
        String name) {

    public static UniversitySummary from(University university) {
        return new UniversitySummary(university.getId(), university.getName());
    }
}
