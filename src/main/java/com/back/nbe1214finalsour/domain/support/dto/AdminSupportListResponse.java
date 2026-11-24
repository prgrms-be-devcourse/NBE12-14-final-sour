package com.back.nbe1214finalsour.domain.support.dto;

import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

/** 관리자 문의 목록 (추가 명세 §3-9). 정렬은 최신순 (계획 D5) */
public record AdminSupportListResponse(
        @Schema(description = "문의 목록")
        List<AdminSupportListItem> items,
        @Schema(description = "페이지 번호", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 문의 수", example = "1")
        long totalCount,
        @Schema(description = "다음 페이지 여부", example = "false")
        boolean hasNext) {

    public static AdminSupportListResponse from(Page<CustomerSupport> result) {
        return new AdminSupportListResponse(result.map(AdminSupportListItem::from).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.hasNext());
    }
}
