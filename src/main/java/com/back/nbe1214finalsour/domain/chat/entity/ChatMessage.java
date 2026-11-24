package com.back.nbe1214finalsour.domain.chat.entity;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Duration;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * 채팅 메시지 한 건. DynamoDB {@code chat_message} 테이블의 항목이다 (테이블 이름은 클래스 이름에서 정해진다).
 *
 * <ul>
 *   <li>파티션 키 {@code room_id}: {@link ChatRoomId}</li>
 *   <li>정렬 키 {@code message_id}: 작성 시각으로 만든 ULID. 시간순으로 정렬되므로 최신순 조회와 커서에 그대로 쓴다</li>
 *   <li>작성자 닉네임과 학교명은 보낸 시점의 값을 복사해 둔다. 닉네임을 바꿔도 지난 메시지는 그대로다</li>
 *   <li>{@code ttl}: 이 시각(epoch 초)이 지나면 DynamoDB가 항목을 지운다. 지우기 전까지는 조회에 나오므로
 *       {@link #isExpired}로 거른다</li>
 * </ul>
 *
 * <p>Enhanced Client가 기본 생성자와 getter·setter로 값을 채우므로 가변 bean으로 둔다. 새 메시지는 {@link #create}로 만든다.
 */
@DynamoDbBean
public class ChatMessage {

    /** DynamoDB TTL로 지정한 속성 이름. 테이블 설정과 같아야 한다 */
    public static final String TTL_ATTRIBUTE = "ttl";

    private String roomId;
    private String messageId;
    private Long userId;
    private String nickname;
    private String universityName;
    private String content;
    private Instant createdAt;
    private Long ttl;

    public ChatMessage() {
    }

    /**
     * 새 메시지를 만든다. ID와 만료 시각은 모두 {@code createdAt}에서 계산한다.
     *
     * @param retention 보관 기간. 대학 채팅은 7일(계획 D9)
     */
    public static ChatMessage create(String roomId, Long userId, String nickname, String universityName,
                                     String content, Instant createdAt, Duration retention) {
        ChatMessage message = new ChatMessage();
        // 같은 밀리초에 여러 건이 생겨도 만든 순서대로 정렬되도록 monotonic ULID를 쓴다
        message.messageId = UlidCreator.getMonotonicUlid(createdAt.toEpochMilli()).toString();
        message.roomId = roomId;
        message.userId = userId;
        message.nickname = nickname;
        message.universityName = universityName;
        message.content = content;
        message.createdAt = createdAt;
        message.ttl = createdAt.plus(retention).getEpochSecond();
        return message;
    }

    /** TTL이 지났는지. DynamoDB는 만료 뒤 며칠 안에 지우므로, 그 사이에는 조회 결과에서 직접 거른다 */
    public boolean isExpired(Instant now) {
        return this.ttl != null && this.ttl <= now.getEpochSecond();
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("room_id")
    public String getRoomId() {
        return this.roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("message_id")
    public String getMessageId() {
        return this.messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    @DynamoDbAttribute("user_id")
    public Long getUserId() {
        return this.userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("nickname")
    public String getNickname() {
        return this.nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @DynamoDbAttribute("university_name")
    public String getUniversityName() {
        return this.universityName;
    }

    public void setUniversityName(String universityName) {
        this.universityName = universityName;
    }

    @DynamoDbAttribute("content")
    public String getContent() {
        return this.content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @DynamoDbAttribute("created_at")
    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** DynamoDB TTL은 Number 타입의 epoch 초여야 한다 (밀리초나 문자열이면 TTL이 동작하지 않는다) */
    @DynamoDbAttribute(TTL_ATTRIBUTE)
    public Long getTtl() {
        return this.ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }
}
