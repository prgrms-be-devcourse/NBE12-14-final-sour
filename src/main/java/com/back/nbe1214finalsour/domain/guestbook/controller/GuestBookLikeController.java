package com.back.nbe1214finalsour.domain.guestbook.controller;

import com.back.nbe1214finalsour.domain.guestbook.controller.docs.GuestBookLikeApiDocs;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookLikeResponse;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookLikeService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/guestbooks")
public class GuestBookLikeController implements GuestBookLikeApiDocs {

    private final GuestBookLikeService guestBookLikeService;

    @Override
    @PostMapping("/{guestbookId}/likes")
    public ApiResponse<GuestBookLikeResponse> like(
            @PathVariable Long guestbookId,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(guestBookLikeService.like(guestbookId, authUser.userId()));
    }

    @Override
    @DeleteMapping("/{guestbookId}/likes")
    public ApiResponse<GuestBookLikeResponse> unlike(
            @PathVariable Long guestbookId,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(guestBookLikeService.unlike(guestbookId, authUser.userId()));
    }
}
