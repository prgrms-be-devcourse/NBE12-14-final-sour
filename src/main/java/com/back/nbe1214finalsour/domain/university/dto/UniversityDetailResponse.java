package com.back.nbe1214finalsour.domain.university.dto;

import java.util.List;

public record UniversityDetailResponse(
        Long id,
        String name,
        String logoUrl,
        Long totalScore,
        Long rank,
        Long memberCount,
        List<TopDepartment> topDepartments,
        List<TopContributor> topContributors,
        boolean isMyUniversity,
        // 보는 사람의 학교와 지금 같은 연합인지. 비로그인·내 학교는 false. SCRUM-187 연합 지원
        boolean isMyAlly,
        // 00:00 KST 복사본 대비 순위 변동, 양수가 상승. 순위가 없거나 비교할 복사본이 없으면 null. SCRUM-189
        Long rankChange
) {
    // 해당 학교에서 점수가 높은 학과 정보
    public record TopDepartment(
            Long rank,
            Long id,
            String name,
            Long totalScore
    ) {
    }

    // 해당 학교에서 기여도가 높은 사용자 정보
    public record TopContributor(
            Long rank,
            Long userId,
            String nickname,
            Long contribScore
    ) {
    }
}
