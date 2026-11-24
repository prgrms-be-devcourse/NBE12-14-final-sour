package com.back.nbe1214finalsour.domain.chat.dto;

import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * 구독자가 받는 채팅 메시지 (계획 §3-2, 명세서 §3-7). Redis 채널로 서버 간에 전달하는 본문도 같은 형식이다.
 *
 * @param createdAt 서버 시각. 한국 시간 오프셋({@code +09:00})으로 나간다
 */
public record ChatMessageResponse(
        @Schema(description = "메시지 ULID", example = "01ARZ3NDEKTSV4RRFFQ69G5FAV")
        String messageId,
        @Schema(description = "채팅방 ID", example = "UNIV#1")
        String roomId,
        @Schema(description = "작성자 ID", example = "1")
        Long userId,
        @Schema(description = "작성자 닉네임", example = "구름이")
        String nickname,
        @Schema(description = "작성자 학교 이름", example = "예시대학교")
        String universityName,
        @Schema(description = "금칙어가 가려진 메시지 본문", example = "안녕하세요!")
        String content,
        @Schema(description = "작성 시각 (한국 시간)", example = "2026-11-23T12:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getMessageId(),
                message.getRoomId(),
                message.getUserId(),
                message.getNickname(),
                message.getUniversityName(),
                message.getContent(),
                message.getCreatedAt().atZone(ClockConfig.KST).toOffsetDateTime());
    }
}
