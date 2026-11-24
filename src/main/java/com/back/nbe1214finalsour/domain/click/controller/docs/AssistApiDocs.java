package com.back.nbe1214finalsour.domain.click.controller.docs;

import com.back.nbe1214finalsour.domain.click.dto.AssistRequest;
import com.back.nbe1214finalsour.domain.click.dto.AssistResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.click.controller.AssistController}의 API 문서. */
@Tag(name = "클릭")
public interface AssistApiDocs {

    @Operation(
            summary = "연합 학교 지원",
            description = """
                    모은 클릭으로 같은 연합의 다른 학교 점수를 올린다.

                    - 대상 학교 점수 += [clickCount × 클리커 클릭 파워 × 0.3]
                    - 내 기여도 += [clickCount × 클리커 클릭 파워 × 아이템 배율]
                    - 내 학교·학과 점수는 그대로이고, 도서관 보너스는 적용하지 않는다
                    - 내 학교나 연합이 아닌 학교는 지원할 수 없다. 이때는 제출 ID와 요청 한도를 쓰지 않는다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ClickErrorDocs.INVALID_INPUT + " / `ASSIST_NOT_ALLY` 내 학교이거나 같은 연합이 아닌 학교"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ClickErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = ClickErrorDocs.FORBIDDEN),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`UNIV_NOT_FOUND` 대상 학교 없음 / " + ClickErrorDocs.USER_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = ClickErrorDocs.DUPLICATE_SUBMISSION),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = ClickErrorDocs.TOO_MANY_REQUESTS)
    })
    ApiResponse<AssistResponse> assist(AuthUser user, AssistRequest request);
}
