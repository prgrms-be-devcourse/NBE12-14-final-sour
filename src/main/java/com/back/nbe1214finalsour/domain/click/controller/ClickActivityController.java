package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.controller.docs.ClickActivityApiDocs;
import com.back.nbe1214finalsour.domain.click.dto.UniversityActivityResponse;
import com.back.nbe1214finalsour.domain.click.service.ClickActivityService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ClickActivityController implements ClickActivityApiDocs {

    private final ClickActivityService clickActivityService;

    @Override
    @GetMapping("/universities/{universityId}/activity")
    public ApiResponse<UniversityActivityResponse> getActivity(
            @PathVariable Long universityId
    ) {
        return ApiResponse.ok(clickActivityService.getActivity(universityId));
    }
}
