package com.back.nbe1214finalsour.domain.chat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.dto.ChatMessageResponse;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.chat.relay.ChatMessagePublisher;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import com.back.nbe1214finalsour.domain.chat.websocket.ChatDestinations;
import com.back.nbe1214finalsour.domain.chat.websocket.RawStompClient;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 대학 채팅 보내기 (SCRUM-130). 실제 서버에 STOMP로 연결해 보내고, 방 구독과 에러 큐로 결과를 받는다.
 * PostgreSQL 저장소와 Redis도 실제로 쓴다. 저장·발행 실패만 스파이로 만든다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatSendIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String SEND_DESTINATION = "/app/chat/university";
    private static final String ROOM_SUBSCRIPTION = "sub-room";
    private static final String ERROR_SUBSCRIPTION = "sub-err";
    private static final Duration NO_MESSAGE_WAIT = Duration.ofMillis(700);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UniversityCache universityCache;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private ChatMessagePublisher chatMessagePublisher;

    private long universityId;
    private User user;
    private RawStompClient client;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        this.universityId = this.jdbcTemplate.queryForObject(
                "INSERT INTO university (name, logo_url) VALUES (?, 'https://cdn.test/u/logo.png') RETURNING id",
                Long.class, "채팅테스트대" + suffix);
        this.jdbcTemplate.update("INSERT INTO chat_room (university_id, university_name) SELECT id, name FROM university WHERE id = ?", this.universityId);
        // 학교 이름은 앱이 뜰 때 읽어 두므로, 테스트가 만든 학교를 넣으려면 다시 읽는다
        this.universityCache.load();
        Long departmentId = this.jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                this.universityId);
        String email = "chat-" + suffix + "@chat-test.ac.kr";
        this.user = this.userRepository.saveAndFlush(User.create(this.universityId, departmentId, "채팅왕" + suffix, email,
                this.emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
        this.client = connectAndSubscribe();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.close();
        this.redis.delete(RedisKey.RATELIMIT_CHAT.of(this.user.getId()));
        this.jdbcTemplate.update("DELETE FROM chat_message WHERE chat_room_id = ?", this.universityId);
        this.jdbcTemplate.update("DELETE FROM chat_room WHERE university_id = ?", this.universityId);
        this.jdbcTemplate.update("DELETE FROM users WHERE id = ?", this.user.getId());
        this.jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", this.universityId);
        this.jdbcTemplate.update("DELETE FROM university WHERE id = ?", this.universityId);
        this.universityCache.load();
    }

    private RawStompClient connectAndSubscribe() throws Exception {
        RawStompClient stomp = RawStompClient.open(this.port, ALLOWED_ORIGIN);
        stomp.connect(this.jwtProvider.createAccessToken(
                new AuthUser(this.user.getId(), this.universityId, null, Role.USER), null));
        assertThat(stomp.requireFrame()).startsWith("CONNECTED\n");
        stomp.subscribe(ROOM_SUBSCRIPTION, ChatDestinations.university(this.universityId));
        stomp.subscribe(ERROR_SUBSCRIPTION, ChatDestinations.ERROR_QUEUE);
        awaitSubscribed(stomp);
        return stomp;
    }

    /** SimpleBroker는 SUBSCRIBE에 RECEIPT를 주지 않으므로, 두 구독 모두에 준비 메시지가 올 때까지 되풀이해 보내고 받은 것은 버린다 */
    private void awaitSubscribed(RawStompClient stomp) throws Exception {
        boolean room = false;
        boolean errors = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!(room && errors) && System.nanoTime() < deadline) {
            this.messagingTemplate.convertAndSend(ChatDestinations.university(this.universityId), (Object) Map.of("probe", true));
            this.messagingTemplate.convertAndSendToUser(String.valueOf(this.user.getId()), "/queue/errors",
                    (Object) Map.of("probe", true));
            for (String frame = stomp.nextFrame(Duration.ofMillis(200)); frame != null;
                 frame = stomp.nextFrame(Duration.ofMillis(100))) {
                room |= frame.contains("\nsubscription:" + ROOM_SUBSCRIPTION + "\n");
                errors |= frame.contains("\nsubscription:" + ERROR_SUBSCRIPTION + "\n");
            }
        }
        assertThat(room && errors).as("5초 안에 두 구독이 등록됨").isTrue();
        while (stomp.nextFrame(Duration.ofMillis(300)) != null) {
            // 늦게 온 준비 메시지를 버린다
        }
    }

    private void send(String body) throws Exception {
        this.client.sendJson(SEND_DESTINATION, body);
    }

    private void sendContent(String content) throws Exception {
        send(this.jsonMapper.writeValueAsString(Map.of("content", content)));
    }

    /** 다음 프레임이 이 구독으로 온 MESSAGE인지 확인하고 본문을 돌려준다 */
    private JsonNode nextMessage(String subscription) throws Exception {
        String frame = this.client.requireFrame();
        assertThat(frame).startsWith("MESSAGE\n").contains("\nsubscription:" + subscription + "\n");
        return this.jsonMapper.readTree(frame.substring(frame.indexOf("\n\n") + 2));
    }

    private void assertError(String code) throws Exception {
        assertThat(nextMessage(ERROR_SUBSCRIPTION).get("code").asString()).isEqualTo(code);
    }

    private void assertNoMoreFrames() throws Exception {
        @Nullable String frame = this.client.nextFrame(NO_MESSAGE_WAIT);
        assertThat(frame).isNull();
    }

    private List<ChatMessage> stored() {
        return this.chatMessageRepository.findByChatRoomIdOrderByIdDesc(this.universityId, org.springframework.data.domain.PageRequest.of(0, 20));
    }

    private void resetRateLimit() {
        this.redis.delete(RedisKey.RATELIMIT_CHAT.of(this.user.getId()));
    }

    @Test
    @DisplayName("보내면 저장되고, 방 구독자(보낸 사람 포함)가 보낸 시점의 닉네임·학교명과 함께 받는다")
    void send_savedAndDelivered() throws Exception {
        sendContent("  오늘 안에 2등 가자  ");

        JsonNode message = nextMessage(ROOM_SUBSCRIPTION);
        assertThat(message.get("roomId").asString()).isEqualTo("UNIV#" + this.universityId);
        assertThat(message.get("userId").asLong()).isEqualTo(this.user.getId());
        assertThat(message.get("nickname").asString()).isEqualTo(this.user.getNickname());
        assertThat(message.get("universityName").asString()).startsWith("채팅테스트대");
        // 앞뒤 공백은 지운다
        assertThat(message.get("content").asString()).isEqualTo("오늘 안에 2등 가자");
        assertThat(message.get("createdAt").asString()).endsWith("+09:00");

        assertThat(stored()).singleElement().satisfies(saved -> {
            assertThat(saved.getId()).isEqualTo(message.get("messageId").asLong());
            assertThat(saved.getContent()).isEqualTo("오늘 안에 2등 가자");
        });
        assertNoMoreFrames();
    }

    @Test
    @DisplayName("금칙어는 ** 하나로 가려 저장·전달하고 원문은 어디에도 남기지 않는다")
    void forbiddenWords_masked() throws Exception {
        sendContent("너 바보야 BadWord");

        assertThat(nextMessage(ROOM_SUBSCRIPTION).get("content").asString()).isEqualTo("너 **야 **");
        assertThat(stored()).singleElement().satisfies(saved -> {
            assertThat(saved.getContent()).isEqualTo("너 **야 **");
            assertThat(saved.getContent()).doesNotContain("바보").doesNotContainIgnoringCase("badword");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT last_content FROM chat_room WHERE university_id = ?", String.class, this.universityId))
                    .isEqualTo("너 **야 **");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT last_content_at FROM chat_room WHERE university_id = ?", OffsetDateTime.class, this.universityId))
                    .isEqualTo(saved.getCreatedAt());
        });
    }

    @Test
    @DisplayName("1초 안에 다시 보내면 CHAT_TOO_FREQUENT이고, 연결은 유지된다")
    void tooFrequent() throws Exception {
        sendContent("첫 번째");
        assertThat(nextMessage(ROOM_SUBSCRIPTION).get("content").asString()).isEqualTo("첫 번째");

        sendContent("두 번째");
        assertError("CHAT_TOO_FREQUENT");
        assertThat(stored()).hasSize(1);

        // 연결이 살아 있어 한도가 풀리면 다시 보낼 수 있다
        resetRateLimit();
        sendContent("세 번째");
        assertThat(nextMessage(ROOM_SUBSCRIPTION).get("content").asString()).isEqualTo("세 번째");
    }

    @Test
    @DisplayName("빈 본문·공백만·201자는 CHAT_INVALID_CONTENT이고 한도를 쓰지 않는다. 200자(이모지 포함)는 보낼 수 있다")
    void invalidContent() throws Exception {
        for (String content : List.of("", "   ", "가".repeat(201))) {
            sendContent(content);
            assertError("CHAT_INVALID_CONTENT");
        }
        // content가 없는 JSON, 본문이 아예 없는 프레임
        for (String body : List.of("{}", "")) {
            send(body);
            assertError("CHAT_INVALID_CONTENT");
        }
        assertThat(stored()).isEmpty();

        // 이모지는 UTF-16으로 2칸이지만 1자로 센다. 위 실패들이 한도를 쓰지 않았으므로 바로 보낼 수 있다
        String twoHundred = "😀".repeat(100) + "가".repeat(100);
        sendContent(twoHundred);
        assertThat(nextMessage(ROOM_SUBSCRIPTION).get("content").asString()).isEqualTo(twoHundred);
    }

    @Test
    @DisplayName("JSON이 아닌 본문은 COMMON_INVALID_INPUT이고 연결은 유지된다")
    void malformedJson() throws Exception {
        send("{깨진 JSON");
        assertError("COMMON_INVALID_INPUT");

        sendContent("정상");
        assertThat(nextMessage(ROOM_SUBSCRIPTION).get("content").asString()).isEqualTo("정상");
    }

    @Test
    @DisplayName("연결 뒤 정지된 회원이 보내면 USER_SUSPENDED (보낼 때마다 상태를 다시 본다)")
    void suspendedAfterConnect() throws Exception {
        this.jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", this.user.getId());

        sendContent("정지된 뒤");

        assertError("USER_SUSPENDED");
        assertThat(stored()).isEmpty();
        assertNoMoreFrames();
    }

    @Test
    @DisplayName("저장에 실패하면 보낸 사람에게만 COMMON_INTERNAL_ERROR이고 발행하지 않는다")
    void saveFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("메시지 저장소 연결 실패")).when(this.chatMessageRepository).save(any());

        sendContent("저장 실패");

        assertError("COMMON_INTERNAL_ERROR");
        verify(this.chatMessagePublisher, never()).publishToUniversity(anyLong(), any(ChatMessageResponse.class));
        assertNoMoreFrames();
    }

    @Test
    @DisplayName("저장 뒤 발행에 실패하면 에러를 보내지 않고 저장은 남는다")
    void publishFails() throws Exception {
        doThrow(new QueryTimeoutException("Redis 연결 끊김"))
                .when(this.chatMessagePublisher).publishToUniversity(anyLong(), any(ChatMessageResponse.class));

        sendContent("발행 실패");

        assertNoMoreFrames();
        assertThat(stored()).singleElement()
                .satisfies(saved -> assertThat(saved.getContent()).isEqualTo("발행 실패"));
    }
}
