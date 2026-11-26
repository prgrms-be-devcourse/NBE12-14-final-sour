package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.controller.docs.AllianceMatchApiDocs;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchCreateRequest;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceMatchService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/alliances")
public class AllianceMatchController implements AllianceMatchApiDocs {
    private final AllianceMatchService matchService;

    // 연합 A 회원이 연합 B에 대결 신청
    @Override
    @PostMapping("/{allianceId}/matches")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AllianceMatchResponse> requestMatch(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long allianceId,
            @Valid @RequestBody AllianceMatchCreateRequest request) {
        return ApiResponse.ok(new AllianceMatchResponse(matchService.requestMatch(
                authUser.userId(), allianceId, request.opponentAllianceId(),
                request.durationHours(), request.scheduledStartAt())));
    }

    // 상대 연합 B 회원이 수락
    @Override
    @PatchMapping("/matches/{matchId}/accept")
    public ApiResponse<AllianceMatchResponse> acceptMatch(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long matchId) {
        return ApiResponse.ok(new AllianceMatchResponse(matchService.acceptMatch(authUser.userId(), matchId)));
    }

    // A 회원의 신청 철회 또는 B 회원의 거절
    @Override
    @PatchMapping("/matches/{matchId}/cancel")
    public ApiResponse<AllianceMatchResponse> cancelMatch(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable Long matchId) {
        return ApiResponse.ok(new AllianceMatchResponse(matchService.cancelMatch(authUser.userId(), matchId)));
    }

}
