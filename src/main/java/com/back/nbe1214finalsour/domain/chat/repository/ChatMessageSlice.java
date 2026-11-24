package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import java.util.List;

/**
 * 최신순 메시지 한 페이지.
 *
 * @param items      최신 메시지부터
 * @param nextCursor 다음(더 오래된) 페이지를 읽을 커서. 마지막 항목의 messageId이며, 다음 페이지가 없으면 null
 * @param hasNext    더 오래된 메시지가 있는지
 */
public record ChatMessageSlice(List<ChatMessage> items, String nextCursor, boolean hasNext) {
}
