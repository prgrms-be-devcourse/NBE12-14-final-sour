package com.back.nbe1214finalsour.domain.alliance.controller;

import com.back.nbe1214finalsour.domain.alliance.controller.docs.AllianceListApiDocs;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.*;
import com.back.nbe1214finalsour.domain.alliance.dto.AllianceMatchResponse;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceListService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.back.nbe1214finalsour.domain.alliance.dto.AllianceLists.DEFAULT_SIZE;

@RestController
@RequestMapping("/api/v1/alliances")
public class AllianceListController implements AllianceListApiDocs {
    private final AllianceListService lists;

    public AllianceListController(AllianceListService lists) {
        this.lists = lists;
    }

    @Override
    @GetMapping
    public ApiResponse<Page<Summary>> directory(@AuthenticationPrincipal AuthUser user,
            @RequestParam(defaultValue = "DIRECTORY") Scope scope,
            @RequestParam(required = false) AllianceStatus status,
            @RequestParam(defaultValue = "false") boolean recruiting,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(lists.directory(user.universityId(), scope, status, recruiting, cursor, size));
    }

    @Override
    @GetMapping("/{allianceId}/universities")
    public ApiResponse<List<School>> schools(@PathVariable long allianceId) {
        return ApiResponse.ok(lists.schools(allianceId));
    }

    @Override
    @GetMapping("/{allianceId}/applications")
    public ApiResponse<Page<Application>> received(@AuthenticationPrincipal AuthUser user, @PathVariable long allianceId,
            @RequestParam(required = false) AllianceUniversityStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(lists.received(allianceId, user.universityId(), status, cursor, size));
    }

    @Override
    @GetMapping("/applications/me")
    public ApiResponse<Page<Application>> sent(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) AllianceUniversityStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(lists.sent(user.universityId(), status, cursor, size));
    }

    @Override
    @GetMapping("/{allianceId}/matches")
    public ApiResponse<Page<Match>> matches(@AuthenticationPrincipal AuthUser user, @PathVariable long allianceId,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) AllianceMatchStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size) {
        return ApiResponse.ok(lists.matches(allianceId, user.universityId(), direction, status, cursor, size));
    }

    @Override
    @GetMapping("/matches/{matchId}")
    public ApiResponse<AllianceMatchResponse> match(@AuthenticationPrincipal AuthUser user, @PathVariable long matchId) {
        return ApiResponse.ok(new AllianceMatchResponse(lists.match(matchId, user.universityId())));
    }
}
