package com.back.nbe1214finalsour.domain.click.controller.docs;

import com.back.nbe1214finalsour.domain.click.dto.AttackRequest;
import com.back.nbe1214finalsour.domain.click.dto.AttackResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.click.controller.AttackController}의 API 문서. */
@Tag(name = "클릭")
public interface AttackApiDocs {

    @Operation(
            summary = "다른 학교 공격",
            description = """
                    모은 클릭으로 다른 학교의 점수를 깎는다.

                    - 피해량 = [clickCount × 클리커 클릭 파워 × 0.3], 라이벌 학교면 × 0.33 (배율 1.1)
                    - 내 학교와 같은 연합의 학교는 공격할 수 없다. 이때는 제출 ID와 요청 한도를 쓰지 않는다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ClickErrorDocs.INVALID_INPUT + " / `ATTACK_OWN_UNIVERSITY` 내 학교 공격 / "
                            + "`ATTACK_ALLY_UNIVERSITY` 같은 연합의 학교 공격"),
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
    ApiResponse<AttackResponse> attack(AuthUser user, AttackRequest request);
}
