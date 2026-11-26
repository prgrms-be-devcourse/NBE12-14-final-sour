package com.back.nbe1214finalsour.domain.university.controller.docs;

import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** {@link com.back.nbe1214finalsour.domain.university.controller.UniversityMapController}의 API 문서. */
@Tag(name = "지도", description = "지도에 표시할 학교 정보 조회")
public interface UniversityMapApiDocs {

    @Operation(
            summary = "지도 학교 목록 조회",
            description = """
            점수 상위 학교의 순위, 이름, 로고, 점수 및 도서관 좌표를 조회한다. 로그인 없이 조회할 수 있다.
            - limit은 1~300 (기본 50).
            - 조회 결과는 Redis에 5초 동안 캐시되므로 최신 점수 반영에 지연이 있을 수 있다.
            - 동점은 공동 순위로 처리하며 랭킹 데이터가 없으면 빈 목록을 반환한다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = RankingErrorDocs.INVALID_LIMIT)
    })
    ApiResponse<List<UniversityMapResponse>> getUniversities(
            @Parameter(description = "최대 학교 수", example = "50") @Min(1) @Max(300) int limit
    );
}
