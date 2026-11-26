package com.back.nbe1214finalsour.domain.university.controller.docs;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityRankingResponse;
import com.back.nbe1214finalsour.domain.university.dto.UserRankingResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** {@link com.back.nbe1214finalsour.domain.university.controller.RankingController}의 API 문서. */
@Tag(name = "랭킹", description = "전국 학교 및 교내 학과·개인 랭킹 조회")
public interface RankingApiDocs {

    @Operation(
            summary = "전국 대학교 랭킹",
            description = """
            대학교 점수 내림차순으로 조회한다. 로그인 없이 조회할 수 있다.
            - page는 0부터 시작한다 (기본 0). size는 1~100 (기본 20).
            - 동점은 공동 순위로 처리한다 (예: 1, 1, 3).
            - rankChange는 이전 순위 - 현재 순위이며 양수는 상승, 비교할 순위가 없으면 null이다.
            - myUniversity는 로그인한 사용자의 학교 순위이며 랭킹에 없거나 비로그인이면 null이다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = RankingErrorDocs.INVALID_PAGE)
    })
    ApiResponse<UniversityRankingResponse> getUniversities(
            @Parameter(description = "페이지 번호", example = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기", example = "20") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) AuthUser authUser
    );

    @Operation(
            summary = "교내 학과 랭킹",
            description = """
            해당 학교의 학과 점수 내림차순으로 조회한다. 로그인 없이 조회할 수 있다.
            - page는 0부터 시작한다 (기본 0). size는 1~100 (기본 20).
            - 동점은 공동 순위로 처리한다 (예: 1, 1, 3).
            - rankChange는 이전 순위 - 현재 순위이며 양수는 상승, 비교할 순위가 없으면 null이다.
            - myDepartment는 내 학교의 학과 랭킹을 조회하고 내 학과 순위가 있을 때만 반환한다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = RankingErrorDocs.INVALID_PAGE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = RankingErrorDocs.UNIVERSITY_NOT_FOUND)
    })
    ApiResponse<DepartmentRankingResponse> getDepartmentRankings(
            @Parameter(description = "대학교 ID", example = "1") Long universityId,
            @Parameter(description = "페이지 번호", example = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기", example = "20") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) AuthUser authUser
    );

    @Operation(
            summary = "교내 개인 랭킹",
            description = """
            해당 학교의 개인 기여도 내림차순으로 조회한다. 로그인 없이 조회할 수 있다.
            - page는 0부터 시작한다 (기본 0). size는 1~100 (기본 20).
            - 동점은 공동 순위로 처리한다 (예: 1, 1, 3).
            - rankChange는 이전 순위 - 현재 순위이며 양수는 상승, 비교할 순위가 없으면 null이다.
            - 비공개 회원은 공개 랭킹 계산에서 제외한다.
            - me는 내 학교의 랭킹을 조회하고 내 공개 순위가 있을 때만 반환한다.
            """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = RankingErrorDocs.INVALID_PAGE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = RankingErrorDocs.UNIVERSITY_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = RankingErrorDocs.RANK_CHANGED)
    })
    ApiResponse<UserRankingResponse> getUserRankings(
            @Parameter(description = "대학교 ID", example = "1") Long universityId,
            @Parameter(description = "페이지 번호", example = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기", example = "20") @Min(1) @Max(100) int size,
            @Parameter(hidden = true) AuthUser authUser
    );
}
