package com.back.nbe1214finalsour.domain.click.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record AbuseRankingResponse(
        @Schema(description = "이번 페이지의 순위 목록")
        List<AbuseRankingItem> content,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "최근 3일 안에 기록이 있는 회원 수", example = "3")
        long totalElements,
        @Schema(description = "다음 페이지가 있는지", example = "false")
        boolean hasNext
) {
    public record AbuseRankingItem(
            @Schema(description = "순위", example = "1")
            long rank,
            @Schema(description = "회원 ID", example = "42")
            Long userId,
            @Schema(description = "회원 닉네임", example = "클릭왕")
            String nickname,
            @Schema(description = "부정행위 감지 횟수 (1분 한도 초과 횟수)", example = "5")
            long abuseCount
    ) {}
}
