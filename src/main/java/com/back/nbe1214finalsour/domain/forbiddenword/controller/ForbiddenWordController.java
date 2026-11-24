package com.back.nbe1214finalsour.domain.forbiddenword.controller;

import com.back.nbe1214finalsour.domain.forbiddenword.controller.docs.ForbiddenWordApiDocs;
import com.back.nbe1214finalsour.domain.forbiddenword.dto.ForbiddenWordsResponse;
import com.back.nbe1214finalsour.domain.forbiddenword.service.ForbiddenWordFilter;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 금칙어 목록 조회 (계획 D19). 인증이 필요하다 ({@code /api/**} 기본 규칙) */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/forbidden-words")
public class ForbiddenWordController implements ForbiddenWordApiDocs {

    private final ForbiddenWordFilter forbiddenWordFilter;

    @GetMapping
    public ApiResponse<ForbiddenWordsResponse> getForbiddenWords() {
        return ApiResponse.ok(new ForbiddenWordsResponse(this.forbiddenWordFilter.words()));
    }
}
