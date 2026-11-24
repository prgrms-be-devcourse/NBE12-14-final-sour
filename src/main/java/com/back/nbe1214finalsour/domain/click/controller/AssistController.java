package com.back.nbe1214finalsour.domain.click.controller;

import com.back.nbe1214finalsour.domain.click.controller.docs.AssistApiDocs;
import com.back.nbe1214finalsour.domain.click.dto.AssistRequest;
import com.back.nbe1214finalsour.domain.click.dto.AssistResponse;
import com.back.nbe1214finalsour.domain.click.service.AssistService;
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
@RequestMapping("/api/v1/assists")
public class AssistController implements AssistApiDocs {

    private final AssistService assistService;

    @Override
    @PostMapping
    public ApiResponse<AssistResponse> assist(@AuthenticationPrincipal AuthUser user,
                                              @RequestBody @Valid AssistRequest request) {
        return ApiResponse.ok(assistService.assist(user.userId(), user.universityId(), request));
    }
}
