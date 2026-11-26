package com.back.nbe1214finalsour.domain.clicker.controller.docs;

import com.back.nbe1214finalsour.domain.clicker.dto.ClickerResponse;
import com.back.nbe1214finalsour.domain.clicker.dto.ClickerUpgradeResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/** {@link com.back.nbe1214finalsour.domain.clicker.controller.ClickerController}의 API 문서. */
@Tag(name = "클리커", description = "클릭 한 번의 세기를 정하는 클리커 레벨 조회와 업그레이드")
public interface ClickerApiDocs {

    @Operation(
            summary = "클리커 레벨표",
            description = """
                    레벨별 클릭 세기와 비용을 레벨 오름차순으로 준다. 로그인 없이 조회할 수 있다.

                    - `clickPower`: 그 레벨에서 클릭 1번에 오르는 점수 (아이템 배율 등을 곱하기 전)
                    - `requiredScore`: 그 레벨로 **올릴 때** 차감하는 기여도. 레벨 1은 시작 레벨이라 0
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공")
    })
    ApiResponse<List<ClickerResponse>> getLevelTable();

    @Operation(
            summary = "클리커 업그레이드",
            description = """
                    내 클리커를 한 단계 올리고, 다음 레벨의 `requiredScore`만큼 기여도를 차감한다.

                    - 기여도는 개인 순위 점수이기도 해서, 업그레이드하면 개인 순위가 내려갈 수 있다 (학교 점수는 그대로)
                    - 응답의 `nextLevelRequiredScore`는 그다음 레벨로 올릴 때의 비용. 최고 레벨이 되면 null
                    - 같은 회원이 동시에 여러 번 요청하면 하나만 성공하고 나머지는 409
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ClickerErrorDocs.UPGRADE_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ClickerErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = ClickerErrorDocs.UPGRADE_CONFLICT)
    })
    ApiResponse<ClickerUpgradeResponse> upgrade(AuthUser authUser);
}