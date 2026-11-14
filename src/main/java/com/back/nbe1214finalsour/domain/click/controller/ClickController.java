package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.dto.ClickRequest;
import com.back.nbe1214finalsour.domain.click.dto.ClickResponse;
import com.back.nbe1214finalsour.domain.click.service.ClickService;
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
@RequestMapping("/api/v1/clicks")
public class ClickController {

    private final ClickService clickService;

    @PostMapping
    public ApiResponse<?> batchClick(@AuthenticationPrincipal AuthUser user,
                                     @RequestBody @Valid ClickRequest request) {
        Long userId = user.userId();
        Long universityId = user.universityId();
        Long departmentId = user.departmentId();

        ClickResponse response = clickService.click(userId, universityId, departmentId, request);

        return ApiResponse.ok(response);
    }
}
