package com.back.nbe1214finalsour.domain.chat.controller;

import com.back.nbe1214finalsour.domain.chat.controller.docs.ChatErrorDocs;
import com.back.nbe1214finalsour.domain.chat.dto.ChatMessagePageResponse;
import com.back.nbe1214finalsour.domain.chat.service.ChatHistoryService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 이전 메시지 조회 (SCRUM-131, 계획 §3-1, D8).
 *
 * <p>파라미터 제약이 있어 인터페이스로 분리하면 Hibernate Validator에서 HV000151이 발생한다.
 */
@Tag(name = "채팅", description = "학교·연합 채팅 이전 메시지 조회",
        externalDocs = @ExternalDocumentation(description = "STOMP 실시간 채팅 명세",
                url = "https://github.com/prgrms-be-devcourse/NBE12-14-final-sour/blob/dev/docs/chat-stomp.md"))
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 학교 채팅방의 메시지를 최신순으로 읽는다. 입장하면 {@code cursor} 없이 최신 20개, 위로 스크롤하면 받은 {@code nextCursor}로
     * 그 이전 20개를 읽는다. {@code size}는 1~20이고 기본 20이다.
     */
    @Operation(summary = "학교 채팅 이전 메시지 조회",
            description = "소속 학교의 메시지를 최신순으로 조회한다. cursor를 생략하면 최신부터 조회하며, "
                    + "이전 응답의 nextCursor(양의 정수)를 다음 요청에 전달한다.")
    @Parameter(name = "cursor", description = "이전 응답의 nextCursor(양의 정수)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ChatErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ChatErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = ChatErrorDocs.NOT_MEMBER)
    })
    @GetMapping("/universities/{universityId}/messages")
    public ApiResponse<ChatMessagePageResponse> getUniversityMessages(
            @PathVariable long universityId,
            @RequestParam(required = false) @Positive @Nullable Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ChatHistoryService.MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ApiResponse.ok(this.chatHistoryService.getUniversityMessages(authUser, universityId, cursor, size));
    }

    /** 연합 채팅방의 메시지. 규칙은 학교 방과 같고, 지금 그 연합에 속한 학교의 회원만 읽는다 (SCRUM-182) */
    @Operation(summary = "연합 채팅 이전 메시지 조회",
            description = "현재 소속 연합의 메시지를 최신순으로 조회한다. cursor를 생략하면 최신부터 조회하며, "
                    + "이전 응답의 nextCursor(양의 정수)를 다음 요청에 전달한다.")
    @Parameter(name = "cursor", description = "이전 응답의 nextCursor(양의 정수)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = ChatErrorDocs.INVALID_INPUT),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = ChatErrorDocs.UNAUTHORIZED),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = ChatErrorDocs.NOT_MEMBER + " (소속 연합 없음 포함)")
    })
    @GetMapping("/alliances/{allianceId}/messages")
    public ApiResponse<ChatMessagePageResponse> getAllianceMessages(
            @PathVariable long allianceId,
            @RequestParam(required = false) @Positive @Nullable Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ChatHistoryService.MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ApiResponse.ok(this.chatHistoryService.getAllianceMessages(authUser, allianceId, cursor, size));
    }
}
