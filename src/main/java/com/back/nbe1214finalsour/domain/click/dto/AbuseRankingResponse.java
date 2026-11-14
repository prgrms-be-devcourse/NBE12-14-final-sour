package com.back.nbe1214finalsour.domain.click.dto;

import java.util.List;

public record AbuseRankingResponse(
        List<AbuseRankingItem> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {
    public record AbuseRankingItem(long rank, Long userId, String nickname, long abuseCount) {}
}
