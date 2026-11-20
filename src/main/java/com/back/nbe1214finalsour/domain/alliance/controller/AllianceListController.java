package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceListService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.DEFAULT_SIZE;
import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.MAX_SIZE;

@RestController
@RequestMapping("/api/v1/alliances")
public class AllianceListController {
    private final AllianceListService lists;

    public AllianceListController(AllianceListService lists) {
        this.lists = lists;
    }

    @GetMapping
    public ApiResponse<Page<Summary>> directory(@AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "DIRECTORY") Scope scope,
            @RequestParam(required = false) AllianceStatus status,
            @RequestParam(defaultValue = "false") boolean recruiting,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.directory(user.universityId(), scope, status, recruiting, cursor, size));
    }

    @GetMapping("/{allianceId}/universities")
    public ApiResponse<List<School>> schools(@PathVariable @Positive long allianceId) {
        return ApiResponse.ok(lists.schools(allianceId));
    }

    @GetMapping("/{allianceId}/applications")
    public ApiResponse<Page<Application>> received(@AuthenticationPrincipal AuthUser user, @PathVariable @Positive long allianceId,
            @RequestParam(required = false) AllianceUniversityStatus status,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.received(allianceId, user.universityId(), status, cursor, size));
    }

    @GetMapping("/applications/me")
    public ApiResponse<Page<Application>> sent(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) AllianceUniversityStatus status,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.sent(user.universityId(), status, cursor, size));
    }

    @GetMapping("/{allianceId}/matches")
    public ApiResponse<Page<Match>> matches(@AuthenticationPrincipal AuthUser user, @PathVariable @Positive long allianceId,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) AllianceMatchStatus status,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.ok(lists.matches(allianceId, user.universityId(), direction, status, cursor, size));
    }

    @GetMapping("/matches/{matchId}")
    public ApiResponse<AllianceMatchResponse> match(@AuthenticationPrincipal AuthUser user, @PathVariable @Positive long matchId) {
        return ApiResponse.ok(lists.match(matchId, user.universityId()));
    }
}
