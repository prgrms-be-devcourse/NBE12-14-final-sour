package com.back.nbe1214finalsour.domain.chat.controller;

import com.back.nbe1214finalsour.domain.chat.controller.docs.ChatErrorDocs;
import com.back.nbe1214finalsour.domain.chat.dto.ChatMessagePageResponse;
import com.back.nbe1214finalsour.domain.chat.service.ChatHistoryService;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
@Tag(name = "채팅", description = """
        STOMP는 SockJS 없이 WebSocket `/ws`로 연결한다. CONNECT 헤더는 `Authorization: Bearer <access 토큰>`이다.
        자기 학교 `/topic/chat/universities/{universityId}`, 현재 소속 연합 `/topic/chat/alliances/{allianceId}`를 구독한다.
        `/app/chat/university` 또는 `/app/chat/alliance`로 JSON `{"content":"메시지"}`를 보낸다.
        content는 앞뒤 공백 제거 후 1~200 코드 포인트이며 방은 서버가 정한다.
        프레임 인증·권한 오류는 ERROR 프레임 뒤 연결이 끊긴다. 본문은 `{"code","message"}`다.
        `COMMON_UNAUTHORIZED` 인증 필요, `COMMON_INVALID_TOKEN` 토큰 오류, `COMMON_EXPIRED_TOKEN` 만료는 재발급 후 재연결,
        `AUTH_SESSION_REPLACED`는 로그인 화면, `CHAT_NOT_MEMBER`는 방 소속 아님, `COMMON_FORBIDDEN`은 허용되지 않은 목적지다.
        메시지 처리 오류는 연결을 유지하고 `/user/queue/errors`로 보낸다: `CHAT_INVALID_CONTENT` 본문 오류,
        `CHAT_TOO_FREQUENT` 전송 제한, `USER_SUSPENDED` 이용 제한, `USER_NOT_FOUND` 회원 없음,
        `CHAT_NOT_MEMBER` 연합 소속 없음, `COMMON_INVALID_INPUT` 본문 형식 오류, `COMMON_INTERNAL_ERROR` 서버 오류.
        이전 메시지는 아래 REST API에서 커서로 조회한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatHistoryController {

    /** ULID 26자 (Crockford Base32, 대문자). 저장된 messageId와 같은 형식만 받는다 */
    private static final String ULID_PATTERN = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private final ChatHistoryService chatHistoryService;

    /**
     * 학교 채팅방의 메시지를 최신순으로 읽는다. 입장하면 {@code cursor} 없이 최신 20개, 위로 스크롤하면 받은 {@code nextCursor}로
     * 그 이전 20개를 읽는다. {@code size}는 1~20이고 기본 20이다.
     */
    @Operation(summary = "학교 채팅 이전 메시지 조회",
            description = "소속 학교의 메시지를 최신순으로 조회한다. cursor를 생략하면 최신부터 조회하며, "
                    + "응답의 nextCursor를 다음 요청의 cursor로 전달한다. cursor는 대문자 ULID 26자다.")
    @Parameter(name = "cursor", description = "대문자 ULID 26자. 생략하면 최신부터, 이후 응답의 nextCursor 사용")
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
            @RequestParam(required = false) @Pattern(regexp = ULID_PATTERN) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ChatHistoryService.MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ApiResponse.ok(this.chatHistoryService.getUniversityMessages(authUser, universityId, cursor, size));
    }

    /** 연합 채팅방의 메시지. 규칙은 학교 방과 같고, 지금 그 연합에 속한 학교의 회원만 읽는다 (SCRUM-182) */
    @Operation(summary = "연합 채팅 이전 메시지 조회",
            description = "현재 소속 연합의 메시지를 최신순으로 조회한다. cursor를 생략하면 최신부터 조회하며, "
                    + "응답의 nextCursor를 다음 요청의 cursor로 전달한다. cursor는 대문자 ULID 26자다.")
    @Parameter(name = "cursor", description = "대문자 ULID 26자. 생략하면 최신부터, 이후 응답의 nextCursor 사용")
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
            @RequestParam(required = false) @Pattern(regexp = ULID_PATTERN) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(ChatHistoryService.MAX_PAGE_SIZE) int size,
            @AuthenticationPrincipal AuthUser authUser
    ) {
        return ApiResponse.ok(this.chatHistoryService.getAllianceMessages(authUser, allianceId, cursor, size));
    }
}
