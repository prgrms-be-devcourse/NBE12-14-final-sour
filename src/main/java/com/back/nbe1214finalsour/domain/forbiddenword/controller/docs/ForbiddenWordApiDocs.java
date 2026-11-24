package com.back.nbe1214finalsour.domain.forbiddenword.controller.docs;

import com.back.nbe1214finalsour.domain.forbiddenword.dto.ForbiddenWordsResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.forbiddenword.controller.ForbiddenWordController}의 API 문서. */
@Tag(name = "금칙어", description = "채팅과 방명록에서 사용하는 공통 금칙어 목록")
public interface ForbiddenWordApiDocs {
    @Operation(summary = "금칙어 목록 조회", description = "채팅과 방명록에서 사용하는 공통 금칙어 목록을 조회한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`COMMON_UNAUTHORIZED` 인증 필요 / `COMMON_INVALID_TOKEN` 유효하지 않은 토큰 / "
                            + "`COMMON_EXPIRED_TOKEN` 만료된 토큰 / `AUTH_SESSION_REPLACED` 다른 기기에서 로그인함")
    })
    ApiResponse<ForbiddenWordsResponse> getForbiddenWords();
}
