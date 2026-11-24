package com.back.nbe1214finalsour.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.chat.entity.ChatRoomId;
import io.awspring.cloud.dynamodb.DynamoDbTableNameResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;

// 실제 DynamoDB 대신 Floci(TestcontainersConfiguration)를 쓴다. 테이블은 EmulatorChatTableInitializer가 만든다
@IntegrationTest
class ChatMessageRepositoryIntegrationTest {

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @Autowired
    private DynamoDbTableNameResolver tableNameResolver;

    private String roomId;
    private Instant now;

    // 테스트끼리 같은 컨테이너를 쓰므로 방을 매번 새로 만든다
    @BeforeEach
    void setUp() {
        this.roomId = ChatRoomId.university(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        this.now = Instant.now();
    }

    private ChatMessage saveAt(String roomId, Instant createdAt, String content) {
        return this.chatMessageRepository.save(
                ChatMessage.create(roomId, 1024L, "대대대짱", "서울대학교", content, createdAt, SEVEN_DAYS));
    }

    // 오래된 것부터 저장하고, 저장한 순서(오래된 → 최신)대로 돌려준다
    private List<ChatMessage> saveMessages(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> saveAt(this.roomId, this.now.minusSeconds(count - i), "메시지 " + i))
                .toList();
    }

    private static List<String> ids(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::getMessageId).toList();
    }

    @Test
    @DisplayName("저장한 메시지를 같은 값으로 읽는다")
    void save_thenRead_sameValues() {
        ChatMessage saved = saveAt(this.roomId, this.now, "오늘 안에 2등 가자");

        ChatMessage read = this.chatMessageRepository.findLatest(this.roomId, null, 20).items().getFirst();

        assertThat(read.getRoomId()).isEqualTo(this.roomId);
        assertThat(read.getMessageId()).isEqualTo(saved.getMessageId());
        assertThat(read.getUserId()).isEqualTo(1024L);
        assertThat(read.getNickname()).isEqualTo("대대대짱");
        assertThat(read.getUniversityName()).isEqualTo("서울대학교");
        assertThat(read.getContent()).isEqualTo("오늘 안에 2등 가자");
        assertThat(read.getCreatedAt()).isEqualTo(this.now);
        assertThat(read.getTtl()).isEqualTo(this.now.plus(SEVEN_DAYS).getEpochSecond());
    }

    @Test
    @DisplayName("최신 메시지부터 size개를 읽고, 더 오래된 메시지가 있으면 마지막 ID를 커서로 준다")
    void findLatest_firstPage() {
        List<ChatMessage> saved = saveMessages(5);

        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 2);

        assertThat(ids(slice.items())).containsExactly(saved.get(4).getMessageId(), saved.get(3).getMessageId());
        assertThat(slice.hasNext()).isTrue();
        assertThat(slice.nextCursor()).isEqualTo(saved.get(3).getMessageId());
    }

    @Test
    @DisplayName("커서로 이어 읽으면 겹치거나 빠지는 메시지가 없고, 마지막 페이지는 hasNext=false, 커서 null이다")
    void findLatest_followCursorToEnd() {
        List<ChatMessage> saved = saveMessages(5);

        ChatMessageSlice first = this.chatMessageRepository.findLatest(this.roomId, null, 2);
        ChatMessageSlice second = this.chatMessageRepository.findLatest(this.roomId, first.nextCursor(), 2);
        ChatMessageSlice last = this.chatMessageRepository.findLatest(this.roomId, second.nextCursor(), 2);

        assertThat(ids(second.items())).containsExactly(saved.get(2).getMessageId(), saved.get(1).getMessageId());
        assertThat(ids(last.items())).containsExactly(saved.get(0).getMessageId());
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("남은 메시지 수가 size와 같으면 다음 페이지가 없다")
    void findLatest_exactlySize_hasNoNext() {
        saveMessages(3);

        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 3);

        assertThat(slice.items()).hasSize(3);
        assertThat(slice.hasNext()).isFalse();
        assertThat(slice.nextCursor()).isNull();
    }

    @Test
    @DisplayName("메시지가 없는 방은 빈 페이지다")
    void findLatest_emptyRoom() {
        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 20);

        assertThat(slice.items()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
        assertThat(slice.nextCursor()).isNull();
    }

    @Test
    @DisplayName("다른 방의 메시지는 섞이지 않는다")
    void findLatest_onlyThatRoom() {
        String otherRoom = ChatRoomId.university(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        ChatMessage mine = saveAt(this.roomId, this.now, "우리 방");
        saveAt(otherRoom, this.now.plusSeconds(1), "다른 방");

        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 20);

        assertThat(ids(slice.items())).containsExactly(mine.getMessageId());
    }

    @Test
    @DisplayName("TTL이 지났지만 아직 지워지지 않은 메시지는 읽지 않는다")
    void findLatest_excludesExpired() {
        saveAt(this.roomId, this.now.minus(Duration.ofDays(9)), "만료 1");
        saveAt(this.roomId, this.now.minus(Duration.ofDays(8)), "만료 2");
        ChatMessage older = saveAt(this.roomId, this.now.minusSeconds(10), "유효 1");
        ChatMessage newer = saveAt(this.roomId, this.now, "유효 2");

        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 20);

        assertThat(ids(slice.items())).containsExactly(newer.getMessageId(), older.getMessageId());
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("유효한 메시지가 정확히 size개이고 그 뒤가 만료뿐이면 다음 페이지가 없다")
    void findLatest_expiredAfterPage_hasNoNext() {
        saveAt(this.roomId, this.now.minus(Duration.ofDays(8)), "만료");
        saveAt(this.roomId, this.now.minusSeconds(10), "유효 1");
        saveAt(this.roomId, this.now, "유효 2");

        ChatMessageSlice slice = this.chatMessageRepository.findLatest(this.roomId, null, 2);

        assertThat(slice.items()).hasSize(2);
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("에뮬레이터 테이블은 ttl 속성으로 TTL이 켜져 있다")
    void table_hasTtlOnTtlAttribute() {
        String tableName = this.tableNameResolver.resolve(ChatMessage.class);

        TimeToLiveDescription ttl = this.dynamoDbClient
                .describeTimeToLive(request -> request.tableName(tableName))
                .timeToLiveDescription();

        assertThat(tableName).isEqualTo("chat_message");
        assertThat(ttl.attributeName()).isEqualTo(ChatMessage.TTL_ATTRIBUTE);
        assertThat(ttl.timeToLiveStatus()).isIn(TimeToLiveStatus.ENABLED, TimeToLiveStatus.ENABLING);
    }
}
