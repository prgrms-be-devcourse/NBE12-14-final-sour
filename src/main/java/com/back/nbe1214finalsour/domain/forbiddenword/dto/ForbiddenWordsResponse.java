package com.back.nbe1214finalsour.domain.forbiddenword.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 금칙어 목록 화면 (계획 D19) */
public record ForbiddenWordsResponse(
        @Schema(description = "채팅과 방명록에 공통으로 적용되는 금칙어 목록")
        List<String> words) {
}
