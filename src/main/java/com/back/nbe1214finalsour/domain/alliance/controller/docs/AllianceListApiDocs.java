package com.back.nbe1214finalsour.domain.alliance.controller.docs;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.MAX_SIZE;

/** {@link com.back.nbe1214finalsour.domain.alliance.controller.AllianceListController}의 API 문서. */
@Tag(name = "연합")
public interface AllianceListApiDocs {

    @Operation(
            summary = "연합 목록 조회",
            description = "연합 목록을 ID 내림차순으로 조회한다. scope는 DIRECTORY(기본) 또는 MEMBERSHIP_HISTORY(내 학교 가입 이력)이다. status로 상태를 필터링하고 recruiting=true이면 가입 가능한 활성 연합만 조회한다. cursor는 이전 응답의 nextCursor를 전달한다. 첫 페이지에서는 생략한다. size는 1~50(기본 20), 마지막 페이지의 nextCursor는 null이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<Page<Summary>> directory(
            @Parameter(hidden = true) AuthUser user,
            Scope scope,
            AllianceStatus status,
            boolean recruiting,
            @Positive Long cursor,
            @Min(1) @Max(MAX_SIZE) int size
    );

    @Operation(
            summary = "연합 소속 학교 조회",
            description = "연합에 현재 소속된 학교 목록을 조회한다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.NOT_FOUND)
    })
    ApiResponse<List<School>> schools(
            @Positive long allianceId
    );

    @Operation(
            summary = "받은 가입 신청 조회",
            description = "내 학교가 소속된 연합의 가입 신청을 조회한다. status로 신청 상태를 필터링할 수 있다. cursor는 이전 응답의 nextCursor를 전달한다. 첫 페이지에서는 생략한다. size는 1~50(기본 20), 마지막 페이지의 nextCursor는 null이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.NOT_MEMBER),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.NOT_FOUND)
    })
    ApiResponse<Page<Application>> received(
            @Parameter(hidden = true) AuthUser user,
            @Positive long allianceId,
            AllianceUniversityStatus status,
            @Positive Long cursor,
            @Min(1) @Max(MAX_SIZE) int size
    );

    @Operation(
            summary = "보낸 가입 신청 조회",
            description = "내 학교가 보낸 가입 신청을 조회한다. status로 신청 상태를 필터링할 수 있다. cursor는 이전 응답의 nextCursor를 전달한다. 첫 페이지에서는 생략한다. size는 1~50(기본 20), 마지막 페이지의 nextCursor는 null이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<Page<Application>> sent(
            @Parameter(hidden = true) AuthUser user,
            AllianceUniversityStatus status,
            @Positive Long cursor,
            @Min(1) @Max(MAX_SIZE) int size
    );

    @Operation(
            summary = "연합 대결 목록 조회",
            description = "연합 대결 목록을 조회한다. direction과 status로 필터링할 수 있다. 종료된 대결(FINISHED) 조회는 과거 소속 학교에도 허용된다. cursor는 이전 응답의 nextCursor를 전달한다. 첫 페이지에서는 생략한다. size는 1~50(기본 20), 마지막 페이지의 nextCursor는 null이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.NOT_MEMBER),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.NOT_FOUND)
    })
    ApiResponse<Page<Match>> matches(
            @Parameter(hidden = true) AuthUser user,
            @Positive long allianceId,
            Direction direction,
            AllianceMatchStatus status,
            @Positive Long cursor,
            @Min(1) @Max(MAX_SIZE) int size
    );

    @Operation(
            summary = "연합 대결 상세 조회",
            description = "대결 양쪽 연합의 소속 학교 회원이 조회할 수 있다. 종료된 대결은 과거 소속 학교에도 허용된다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.NOT_MEMBER),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.MATCH_NOT_FOUND)
    })
    ApiResponse<AllianceMatchResponse> match(
            @Parameter(hidden = true) AuthUser user,
            @Positive long matchId
    );
}
