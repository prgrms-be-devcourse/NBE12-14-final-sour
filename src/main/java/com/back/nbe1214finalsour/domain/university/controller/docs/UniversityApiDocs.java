package com.back.nbe1214finalsour.domain.university.controller.docs;

import com.back.nbe1214finalsour.domain.university.dto.DepartmentResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversityDetailResponse;
import com.back.nbe1214finalsour.domain.university.dto.UniversitySearchResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** {@link com.back.nbe1214finalsour.domain.university.controller.UniversityController}의 API 문서. */
@Tag(name = "대학교", description = "대학교 검색, 상세 정보 및 학과 목록 조회")
public interface UniversityApiDocs {

    @Operation(
            summary = "대학교 검색",
            description = """
                    이름에 키워드를 포함하는 공개 학교를 이름 오름차순으로 조회한다. 로그인 없이 조회할 수 있다.

                    - 숨김 학교는 검색 결과에서 제외한다
                    - keyword는 필수이며 공백일 수 없다
                    - size는 1~100 (기본 20). 검색 결과가 없으면 빈 목록을 반환한다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UniversityErrorDocs.SEARCH_INVALID_INPUT)
    })
    ApiResponse<List<UniversitySearchResponse>> searchUniversities(
            @Parameter(description = "대학교 검색 키워드", example = "서울") @NotBlank String keyword,
            @Parameter(description = "조회 개수", example = "20") @Min(1) @Max(100) int size);

    @Operation(
            summary = "대학교 학과 목록",
            description = "해당 학교의 학과를 이름 오름차순으로 조회한다. 로그인 없이 조회할 수 있다. 등록된 학과가 없으면 빈 목록을 반환한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UniversityErrorDocs.INVALID_UNIVERSITY_ID),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = UniversityErrorDocs.UNIVERSITY_NOT_FOUND)
    })
    ApiResponse<List<DepartmentResponse>> getDepartments(
            @Parameter(description = "대학교 ID", example = "1") Long universityId);

    @Operation(
            summary = "대학교 상세 조회",
            description = """
                    학교 기본 정보, 점수, 순위, 회원 수, 상위 학과와 기여자를 조회한다. 로그인 없이 조회할 수 있다.

                    - Redis에서 학교 점수와 순위를 우선 조회한다. 점수와 순위를 함께 구할 수 없으면 DB 점수를 사용한다
                    - 숨김 학교는 공개 순위가 없으며 별도의 Redis 점수가 있으면 그 점수를 사용한다
                    - rank는 순위가 없으면 null. rankChange는 자정(KST) 기준 순위 변동이며 비교할 정보가 없으면 null
                    - 상위 학과와 활성 회원 기여자는 각각 최대 3개를 반환한다
                    - isMyUniversity와 isMyAlly는 비로그인 상태에서 false이다. 내 학교는 isMyAlly가 false이다
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = UniversityErrorDocs.INVALID_UNIVERSITY_ID),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = UniversityErrorDocs.UNIVERSITY_NOT_FOUND)
    })
    ApiResponse<UniversityDetailResponse> getUniversityDetail(
            @Parameter(description = "대학교 ID", example = "1") Long universityId,
            @Parameter(hidden = true) AuthUser authUser);
}
