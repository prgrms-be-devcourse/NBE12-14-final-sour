package com.back.nbe1214finalsour.domain.user.dto;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.global.security.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code GET /api/v1/users/me} 응답 (API 명세서 §3-2 "내 정보").
 *
 * @param email            복호화한 학교 이메일
 * @param profileImageUrl  프로필 이미지 ({@code users.logo_url}). 없으면 {@code null}
 * @param totalContribScore 기여도. Redis 실시간 값, 없으면 DB 값
 * @param totalAttackScore 공격 점수. Redis 실시간 값, 없으면 DB 값
 */
public record MyInfoResponse(
        @Schema(description = "회원 ID", example = "1")
        Long id,
        @Schema(description = "닉네임", example = "학생")
        String nickname,
        @Schema(description = "학교 이메일", example = "student@example.ac.kr")
        String email,
        @Schema(description = "회원 권한", example = "USER")
        Role role,
        @Schema(description = "회원 상태", example = "ACTIVE")
        UserStatus status,
        @Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png")
        String profileImageUrl,
        @Schema(description = "학교 정보")
        UniversityInfo university,
        @Schema(description = "학과 정보")
        DepartmentInfo department,
        @Schema(description = "클리커 정보")
        ClickerInfo clicker,
        @Schema(description = "총 기여도", example = "100")
        long totalContribScore,
        @Schema(description = "총 공격 점수", example = "20")
        long totalAttackScore) {

    /** @param logoUrl 학교 로고 ({@code university.logo_url}). 없으면 {@code null} */
    public record UniversityInfo(
            @Schema(description = "학교 ID", example = "12")
            Long id,
            @Schema(description = "학교 이름", example = "예시대학교")
            String name,
            @Schema(description = "학교 로고 URL", example = "https://example.com/logo.png")
            String logoUrl) {
    }

    public record DepartmentInfo(
            @Schema(description = "학과 ID", example = "301")
            Long id,
            @Schema(description = "학과 이름", example = "컴퓨터공학과")
            String name) {
    }

    /**
     * @param level                  현재 레벨 ({@code clicker.id})
     * @param nextLevelRequiredScore 다음 레벨로 올릴 때 차감하는 기여도. 최고 레벨이면 {@code null}
     */
    public record ClickerInfo(
            @Schema(description = "클리커 레벨", example = "1")
            Long level,
            @Schema(description = "클릭 파워", example = "1")
            int clickPower,
            @Schema(description = "다음 레벨에 필요한 기여도", example = "100")
            Long nextLevelRequiredScore) {
    }
}
