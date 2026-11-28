package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceApplicationResponse;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceInvitationService;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceListService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.DEFAULT_SIZE;
import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.MAX_SIZE;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/alliances")
public class AllianceInvitationController {
    private final AllianceInvitationService invitations;
    private final AllianceListService lists;

    public record InviteRequest(@NotNull @Positive Long universityId) {}

    @Operation(summary = "연합 초대 발송", description = "발송 연합 소속의 활동 중인 회원이 미소속 학교에 초대를 보낸다.")
    @PostMapping("/{allianceId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AllianceApplicationResponse> invite(@AuthenticationPrincipal AuthUser user,
            @PathVariable Long allianceId, @Valid @RequestBody InviteRequest request) {
        return ApiResponse.ok(new AllianceApplicationResponse(invitations.invite(user.userId(), allianceId, request.universityId())));
    }

    @Operation(summary = "내 학교가 받은 연합 초대", description = "approvalBlockReason은 조회 시점의 승인 차단 사유다. 승인은 서버가 다시 검증한다.")
    @GetMapping("/invitations/me")
    public ApiResponse<AllianceLists.Page<AllianceLists.Invitation>> received(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) AllianceUniversityStatus status, @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.receivedInvitations(user.universityId(), status, cursor, size));
    }

    @Operation(summary = "내 연합이 보낸 연합 초대")
    @GetMapping("/{allianceId}/invitations")
    public ApiResponse<AllianceLists.Page<AllianceLists.Invitation>> sent(@AuthenticationPrincipal AuthUser user,
            @PathVariable Long allianceId, @RequestParam(required = false) AllianceUniversityStatus status,
            @RequestParam(required = false) @Positive Long cursor, @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.sentInvitations(allianceId, user.universityId(), status, cursor, size));
    }

    @Operation(summary = "연합 초대 승인", description = "초대받은 학교의 활동 중인 회원만 승인할 수 있다.")
    @PatchMapping("/invitations/{invitationId}/approve")
    public ApiResponse<AllianceApplicationResponse> approve(@AuthenticationPrincipal AuthUser user, @PathVariable Long invitationId) {
        return ApiResponse.ok(new AllianceApplicationResponse(invitations.approve(user.userId(), invitationId)));
    }

    @Operation(summary = "연합 초대 거절", description = "초대받은 학교의 활동 중인 회원만 거절할 수 있다.")
    @PatchMapping("/invitations/{invitationId}/reject")
    public ApiResponse<AllianceApplicationResponse> reject(@AuthenticationPrincipal AuthUser user, @PathVariable Long invitationId) {
        return ApiResponse.ok(new AllianceApplicationResponse(invitations.reject(user.userId(), invitationId)));
    }
}
