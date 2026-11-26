package com.back.nbe1214finalsour.domain.alliance.controller.docs;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceApplicationResponse;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceCreateRequest;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceResponse;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** {@link com.back.nbe1214finalsour.domain.alliance.controller.AllianceController}의 API 문서. */
@Tag(name = "연합")
public interface AllianceApiDocs {

    @Operation(
            summary = "내 연합 조회",
            description = "내 학교의 현재 연합을 조회한다. 소속 연합이 없으면 data는 null이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED)
    })
    ApiResponse<AllianceResponse> getMyAlliance(
            @Parameter(hidden = true) AuthUser authUser
    );

    @Operation(
            summary = "연합 상세 조회",
            description = "연합 ID로 상세 정보를 조회한다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.NOT_FOUND)
    })
    ApiResponse<AllianceResponse> getAlliance(
            Long allianceId
    );

    @Operation(
            summary = "연합 생성",
            description = "활성 회원이 연합을 생성한다. 생성자의 학교는 자동 가입된다. 연합 이름은 50자 이하, 유지 시간은 6~72시간이다. 연합 정원은 학교 2개이다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = AllianceErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.ACTIVE_USER),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.USER_NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.ALREADY_JOINED)
    })
    ApiResponse<AllianceResponse> createAlliance(
            @Parameter(hidden = true) AuthUser authUser,
            AllianceCreateRequest request
    );

    @Operation(
            summary = "연합 가입 신청",
            description = "내 학교의 가입 신청을 PENDING 상태로 등록한다. 이미 가입한 학교, 동일 연합에 대기 중인 신청이 있는 학교, 정원이 찬 연합은 신청할 수 없다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.ACTIVE_USER),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.USER_NOT_FOUND + " / " + AllianceErrorDocs.NOT_FOUND),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.JOIN_CONFLICT)
    })
    ApiResponse<AllianceApplicationResponse> applyToAlliance(
            @Parameter(hidden = true) AuthUser authUser,
            Long allianceId
    );

    @Operation(
            summary = "연합 가입 승인",
            description = "해당 연합 소속 학교의 활성 회원이 대기 중인 신청을 승인한다. 정원이 차면 나머지 대기 신청은 자동 거절된다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.MEMBER_OR_ACTIVE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.APPLICATION_MISSING),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.APPROVE_CONFLICT)
    })
    ApiResponse<AllianceApplicationResponse> approveApplication(
            @Parameter(hidden = true) AuthUser authUser,
            Long applicationId
    );

    @Operation(
            summary = "연합 가입 거절",
            description = "해당 연합 소속 학교의 활성 회원이 대기 중인 신청을 거절한다. 로그인이 필요하다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = AllianceErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = AllianceErrorDocs.MEMBER_OR_ACTIVE),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = AllianceErrorDocs.APPLICATION_MISSING),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = AllianceErrorDocs.APPLICATION_CONFLICT)
    })
    ApiResponse<AllianceApplicationResponse> rejectApplication(
            @Parameter(hidden = true) AuthUser authUser,
            Long applicationId
    );
}
