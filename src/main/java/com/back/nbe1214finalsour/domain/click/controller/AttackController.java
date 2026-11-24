package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.controller.docs.AttackApiDocs;
import com.back.nbe1214finalsour.domain.click.dto.AttackRequest;
import com.back.nbe1214finalsour.domain.click.dto.AttackResponse;
import com.back.nbe1214finalsour.domain.click.service.AttackService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/attacks")
public class AttackController implements AttackApiDocs {

    private final AttackService attackService;

    @Override
    @PostMapping
    public ApiResponse<AttackResponse> attack(@AuthenticationPrincipal AuthUser user,
                                     @RequestBody @Valid AttackRequest request) {
        Long userId = user.userId();
        Long universityId = user.universityId();

        AttackResponse response = attackService.attack(userId, universityId, request);

        return ApiResponse.ok(response);
    }
}
