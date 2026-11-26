package com.back.nbe1214finalsour.domain.guestbook.controller;

import com.back.nbe1214finalsour.domain.guestbook.controller.docs.GuestBookApiDocs;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookCreateRequest;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookQueryService;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookWriteService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/universities/{universityId}/guestbooks")
public class GuestBookController implements GuestBookApiDocs {

    private final GuestBookQueryService guestBookQueryService;
    private final GuestBookWriteService guestBookWriteService;

    @Override
    @GetMapping
    public ApiResponse<GuestBookListResponse> getGuestBooks(
            @PathVariable Long universityId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthUser authUser) {
        Long viewerUserId = authUser == null ? null : authUser.userId();
        return ApiResponse.ok(guestBookQueryService.getGuestBooks(universityId, cursor, size, viewerUserId));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GuestBookListResponse.Item> createGuestBook(
            @PathVariable Long universityId,
            @RequestBody GuestBookCreateRequest request,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.ok(guestBookWriteService.create(universityId, authUser.userId(), request.content()));
    }
}
