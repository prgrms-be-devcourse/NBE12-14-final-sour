package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import io.awspring.cloud.dynamodb.DynamoDbTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

/**
 * DynamoDB {@code chat_message} 테이블 저장소 (SCRUM-127).
 */
@Repository
@RequiredArgsConstructor
public class ChatMessageRepository {

    private final DynamoDbTemplate dynamoDbTemplate;
    private final Clock clock;

    public ChatMessage save(ChatMessage message) {
        return this.dynamoDbTemplate.save(message);
    }

    /**
     * 한 방의 메시지를 최신순으로 {@code size}개까지 읽는다. {@code cursor}가 있으면 그 메시지보다 오래된 것부터 읽는다.
     *
     * <p>TTL이 지났지만 아직 지워지지 않은 메시지는 뺀다. 한 방의 메시지는 같은 보관 기간으로 만들어지므로,
     * 최신순으로 읽다가 처음 만난 만료 메시지보다 오래된 메시지도 모두 만료다. 그래서 거기서 읽기를 멈춘다.
     * (DynamoDB 필터 식으로 거르면 만료된 메시지를 끝까지 읽어 넘기느라 요청이 늘어난다)
     *
     * @param cursor 이전 페이지의 {@link ChatMessageSlice#nextCursor()}. 첫 페이지는 null
     */
    public ChatMessageSlice findLatest(String roomId, @Nullable String cursor, int size) {
        QueryConditional condition = cursor == null
                ? QueryConditional.keyEqualTo(key -> key.partitionValue(roomId))
                : QueryConditional.sortLessThan(key -> key.partitionValue(roomId).sortValue(cursor));

        // 다음 페이지가 있는지 알기 위해 하나 더 읽는다
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(condition)
                .scanIndexForward(false)
                .limit(size + 1)
                .build();

        Instant now = this.clock.instant();
        List<ChatMessage> found = this.dynamoDbTemplate.query(request, ChatMessage.class).items().stream()
                .takeWhile(message -> !message.isExpired(now))
                .limit(size + 1L)
                .toList();

        boolean hasNext = found.size() > size;
        List<ChatMessage> items = hasNext ? found.subList(0, size) : found;
        String nextCursor = hasNext ? items.getLast().getMessageId() : null;
        return new ChatMessageSlice(items, nextCursor, hasNext);
    }
}
