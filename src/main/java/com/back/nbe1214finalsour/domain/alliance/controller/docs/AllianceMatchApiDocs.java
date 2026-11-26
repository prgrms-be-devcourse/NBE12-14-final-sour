package com.back.nbe1214finalsour.domain.alliance.controller.docs;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchCreateRequest;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.alliance.controller.AllianceMatchController}의 API 문서. */
@Tag(name = "연합 대결")
public interface AllianceMatchApiDocs {

    @Operation(
            summary = "연합 대결 신청",
            description = "내 연합에서 상대 연합에 대결을 신청한다. 양쪽 모두 학교 2개가 필요하다. 대결 시간은 1~24시간이며 scheduledStartAt은 선택값이다. 예약 시각은 미래이고 양쪽 연합 만료 전에 대결이 끝나야 한다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.MATCH_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.MEMBER_OR_ACTIVE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.USER_NOT_FOUND + " / " + AllianceErrorDocs.NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.MATCH_REQUEST_CONFLICT)
    })
    ApiResponse<AllianceMatchResponse> requestMatch(
            @Parameter(hidden = true) AuthUser authUser,
            Long allianceId,
            AllianceMatchCreateRequest request
    );

    @Operation(
            summary = "연합 대결 수락",
            description = "상대 연합의 활성 회원만 대기 중인 대결을 수락할 수 있다. 예약 시각이 있으면 SCHEDULED, 없으면 즉시 IN_PROGRESS가 된다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.MATCH_BAD_REQUEST),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.MEMBER_OR_ACTIVE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.MATCH_MISSING),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.MATCH_ACCEPT_CONFLICT)
    })
    ApiResponse<AllianceMatchResponse> acceptMatch(
            @Parameter(hidden = true) AuthUser authUser,
            Long matchId
    );

    @Operation(
            summary = "연합 대결 철회·거절",
            description = "대기 중인 대결 신청을 신청 측 회원이 철회하거나 상대 측 회원이 거절한다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.MEMBER_OR_ACTIVE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.MATCH_MISSING),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.MATCH_CANCEL_CONFLICT)
    })
    ApiResponse<AllianceMatchResponse> cancelMatch(
            @Parameter(hidden = true) AuthUser authUser,
            Long matchId
    );
}
