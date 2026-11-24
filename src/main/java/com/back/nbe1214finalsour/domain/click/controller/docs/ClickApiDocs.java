package com.back.nbe1214finalsour.domain.click.controller.docs;

import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.click.controller.ClickController}의 API 문서. */
@Tag(name = "클릭", description = "클릭·공격·지원으로 학교 점수를 올리거나 깎는다")
public interface ClickApiDocs {

    @Operation(
            summary = "클릭 제출",
            description = """
                    모은 클릭을 한 번에 제출해 내 학교·학과 점수와 내 기여도를 올린다.

                    - 점수 = [clickCount × 클리커 클릭 파워 × 배율]
                    - 배율 = 아이템 배율 × 도서관 보너스(학교 도서관 100m 안이면 1.2)
                    - 위치(latitude·longitude)를 보내지 않으면 도서관 보너스 없이 처리한다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ClickErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ClickErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = ClickErrorDocs.FORBIDDEN),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = ClickErrorDocs.USER_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = ClickErrorDocs.DUPLICATE_SUBMISSION),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = ClickErrorDocs.TOO_MANY_REQUESTS)
    })
    ApiResponse<ClickResponse> batchClick(AuthUser user, ClickRequest request);
}
