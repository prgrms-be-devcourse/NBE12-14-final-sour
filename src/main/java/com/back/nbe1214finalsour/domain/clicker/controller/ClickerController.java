package com.back.nbe1214finalsour.domain.clicker.controller;

import com.back.nbe1214finalsour.domain.clicker.dto.ClickerResponse;
import com.back.nbe1214finalsour.domain.clicker.dto.ClickerUpgradeResponse;
import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerUpgradeResult;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerUpgradeService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/clickers")
public class ClickerController {

    private final ClickerService clickerService;
    private final ClickerUpgradeService clickerUpgradeService;


    @GetMapping
    public ApiResponse<List<ClickerResponse>> getLevelTable() {
        List<ClickerResponse> levelTable = this.clickerService.getLevelTable().stream()
                .map(ClickerResponse::new)
                .toList();

        return ApiResponse.ok(levelTable);
    }

    @PostMapping("/upgrade")
    public ApiResponse<ClickerUpgradeResponse> upgrade(@AuthenticationPrincipal AuthUser authUser) {
        ClickerUpgradeResult result = this.clickerUpgradeService.upgrade(authUser.userId(), authUser.universityId());
        return ApiResponse.ok(new ClickerUpgradeResponse(result));
    }
}
