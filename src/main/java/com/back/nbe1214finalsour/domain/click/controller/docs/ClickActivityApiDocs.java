package com.back.nbe1214finalsour.domain.click.controller.docs;

import com.back.nbe1214finalsour.domain.click.dto.UniversityActivityResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.click.controller.ClickActivityController}의 API 문서. */
@Tag(name = "클릭", description = "클릭·공격·지원으로 학교 점수를 올리거나 깎는다")
public interface ClickActivityApiDocs {

    @Operation(
            summary = "학교 직전 1초 활동 조회",
            description = """
                    학교에서 직전 1초 동안 일어난 클릭·공격·지원 수를 조회한다. 캠퍼스 파티클 연출용이며 \
                    화면을 보는 동안 1초마다 호출한다. 로그인 없이 조회할 수 있다.

                    - clicks: 이 학교 학생들이 한 클릭 수
                    - attacks: 이 학교가 받은 공격 수 / assists: 이 학교가 받은 지원 수
                    - 요청자 본인의 클릭·공격·지원도 포함한다. 본인 몫은 클라이언트가 뺀다
                    - second는 집계한 초(epoch seconds, 서버 현재 초 − 1)다. 같은 second를 다시 받으면 무시한다
                    - 활동이 없던 초는 모두 0이다. 초별 집계는 5초 뒤 사라진다
                    - 숨김 학교도 조회된다. 클릭 요청 한도·중복 제출·어뷰징 확인과는 관계없다
                    """)
    @SecurityRequirements
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ClickErrorDocs.INVALID_UNIVERSITY_ID),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = ClickErrorDocs.UNIVERSITY_NOT_FOUND)
    })
    ApiResponse<UniversityActivityResponse> getActivity(
            @Parameter(description = "대학교 ID", example = "1") Long universityId);
}
