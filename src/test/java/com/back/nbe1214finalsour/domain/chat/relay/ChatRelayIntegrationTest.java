package com.back.nbe1214finalsour.domain.chat.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.ChatRecipientFixture;
import com.back.nbe1214finalsour.domain.chat.dto.ChatMessageResponse;
import com.back.nbe1214finalsour.domain.chat.entity.ChatRoomId;
import com.back.nbe1214finalsour.domain.chat.websocket.ChatDestinations;
import com.back.nbe1214finalsour.domain.chat.websocket.RawStompClient;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버 간 전달 (SCRUM-129, D4·D5). Redis 채널에 직접 발행한 메시지는 다른 서버가 보낸 메시지를 대신한다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatRelayIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final Duration NO_MESSAGE_WAIT = Duration.ofMillis(500);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ChatMessagePublisher publisher;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    @Qualifier(ChatRelayConfig.LISTENER_EXECUTOR)
    private ThreadPoolTaskExecutor listenerExecutor;

    @Autowired
    private SimpleBrokerMessageHandler simpleBroker;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private EmailHasher emailHasher;
    @Autowired private SimpMessagingTemplate messagingTemplate;

    private ChatRecipientFixture recipient;
    private long universityId;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        this.recipient = ChatRecipientFixture.create(this.jdbc, this.users, this.emailHasher);
        this.universityId = this.recipient.universityId();
    }

    @AfterEach
    void tearDown() {
        if (this.recipient != null) this.recipient.remove(this.jdbc);
    }

    private RawStompClient subscribed(long universityId) throws Exception {
        RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN);
        client.connect(this.jwtProvider.createAccessToken(new AuthUser(this.recipient.userId(), universityId,
                this.recipient.departmentId(), Role.USER), null));
        assertThat(client.requireFrame()).startsWith("CONNECTED\n");
        client.subscribe("sub-0", ChatDestinations.university(universityId));
        return client;
    }

    private ChatMessageResponse message(long universityId, String content) {
        return new ChatMessageResponse((long) this.sequence.incrementAndGet(), ChatRoomId.university(universityId),
                7L, "닉네임", "테스트대학교", content, OffsetDateTime.of(2026, 11, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)));
    }

    private void publishRaw(String channel, String body) {
        this.redisTemplate.convertAndSend(channel, body);
    }

    /** SimpleBroker는 SUBSCRIBE에 RECEIPT를 주지 않으므로, 구독이 등록될 때까지 준비 메시지를 되풀이해 발행하고 받은 것은 버린다 */
    private void awaitSubscribed(RawStompClient client, long universityId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            this.publisher.publishToUniversity(universityId, message(universityId, "준비"));
            if (client.nextFrame(Duration.ofMillis(200)) != null) {
                // 되풀이 발행으로 쌓인 준비 메시지를 비운다
                while (client.nextFrame(NO_MESSAGE_WAIT) != null) {
                    // 버림
                }
                return;
            }
        }
        throw new AssertionError("5초 안에 구독이 등록되지 않음");
    }

    private static String body(String frame) {
        return frame.substring(frame.indexOf("\n\n") + 2);
    }

    private @Nullable String nextContent(RawStompClient client) throws Exception {
        String frame = client.nextFrame(Duration.ofSeconds(5));
        if (frame == null) {
            return null;
        }
        return this.jsonMapper.readTree(body(frame)).get("content").asString();
    }

    @Test
    @DisplayName("Redis 채널 chat:univ:{id}에 발행된 메시지를 그 학교 방 구독자가 같은 형식으로 받는다")
    void redisMessage_deliveredToSubscribers() throws Exception {
        try (RawStompClient client = subscribed(this.universityId)) {
            awaitSubscribed(client, this.universityId);

            ChatMessageResponse sent = message(this.universityId, "다른 서버에서 왔어요");
            publishRaw(RedisKey.CHAT_UNIV_CHANNEL.of(this.universityId), this.jsonMapper.writeValueAsString(sent));

            String frame = client.requireFrame();
            assertThat(frame).startsWith("MESSAGE\n");
            assertThat(frame).contains("\ndestination:" + ChatDestinations.university(this.universityId) + "\n");
            assertThat(frame).contains("\ncontent-type:application/json");
            var json = this.jsonMapper.readTree(body(frame));
            assertThat(json.get("messageId").asLong()).isEqualTo(sent.messageId());
            assertThat(json.get("roomId").asString()).isEqualTo("UNIV#" + this.universityId);
            assertThat(json.get("userId").asLong()).isEqualTo(7L);
            assertThat(json.get("nickname").asString()).isEqualTo("닉네임");
            assertThat(json.get("universityName").asString()).isEqualTo("테스트대학교");
            assertThat(json.get("content").asString()).isEqualTo("다른 서버에서 왔어요");
            assertThat(json.get("createdAt").asString()).isEqualTo("2026-11-17T18:30:00+09:00");
        }
    }

    @Test
    @DisplayName("한꺼번에 몰려 발행된 메시지를 발행 순서대로 받는다")
    void burst_keepsPublishOrder() throws Exception {
        try (RawStompClient client = subscribed(this.universityId)) {
            awaitSubscribed(client, this.universityId);

            List<String> expected = new ArrayList<>();
            List<byte[]> bodies = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                expected.add("순서-" + i);
                bodies.add(this.jsonMapper.writeValueAsBytes(message(this.universityId, "순서-" + i)));
            }
            // 응답을 기다리지 않고 한 번에 보내 서버에 메시지가 몰리게 한다
            byte[] channel = RedisKey.CHAT_UNIV_CHANNEL.of(this.universityId).getBytes(StandardCharsets.UTF_8);
            this.redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                bodies.forEach(body -> connection.publish(channel, body));
                return null;
            });

            List<String> received = new ArrayList<>();
            for (int i = 0; i < expected.size(); i++) {
                received.add(nextContent(client));
            }
            assertThat(received).containsExactlyElementsOf(expected);
        }
    }

    /**
     * 순서를 지키는 설정 두 가지를 고정한다. 리스너 실행기를 기본값(메시지마다 새 스레드)으로 되돌리면 위 테스트가 실패한다.
     * 브로커의 {@code preservePublishOrder}는 Spring 문서가 순서 보장 방법으로 안내하는 설정이지만, 위 테스트로는 빠졌을 때의
     * 순서 섞임이 재현되지 않아 설정값을 직접 확인한다.
     */
    @Test
    @DisplayName("순서 보장 설정: Redis 리스너는 스레드 하나, 브로커는 세션별 발행 순서 유지")
    void orderingSettings() {
        assertThat(this.listenerExecutor.getMaxPoolSize()).isEqualTo(1);
        assertThat(this.simpleBroker.isPreservePublishOrder()).isTrue();
    }

    @Test
    @DisplayName("다른 학교 채널의 메시지는 받지 않는다")
    void otherUniversityChannel_notDelivered() throws Exception {
        long otherUniversityId = this.universityId + 1_000_000;
        try (RawStompClient client = subscribed(this.universityId)) {
            awaitSubscribed(client, this.universityId);

            this.publisher.publishToUniversity(otherUniversityId, message(otherUniversityId, "다른 학교"));

            assertThat(client.nextFrame(NO_MESSAGE_WAIT)).isNull();
        }
    }

    @Test
    @DisplayName("탈퇴한 회원의 기존 구독은 외부 Redis 발행의 본문도 받지 않는다")
    void redisMessage_notDeliveredAfterWithdrawal() throws Exception {
        try (RawStompClient client = subscribed(this.universityId)) {
            awaitSubscribed(client, this.universityId);
            client.subscribe("err", ChatDestinations.ERROR_QUEUE);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            String ready = null;
            while (ready == null && System.nanoTime() < deadline) {
                this.messagingTemplate.convertAndSendToUser(String.valueOf(this.recipient.userId()), "/queue/errors", "ready");
                ready = client.nextFrame(Duration.ofMillis(200));
            }
            assertThat(ready).isNotNull().contains("\nsubscription:err\n");
            while (client.nextFrame(NO_MESSAGE_WAIT) != null) { /* 늦은 준비 프레임을 비운다. */ }
            this.jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = now() WHERE id = ?", this.recipient.userId());

            publishRaw(RedisKey.CHAT_UNIV_CHANNEL.of(this.universityId),
                    this.jsonMapper.writeValueAsString(message(this.universityId, "탈퇴 이후 본문")));

            String frame = client.requireFrame();
            assertThat(frame).startsWith("MESSAGE\n").contains("\nsubscription:err\n");
            assertThat(this.jsonMapper.readTree(body(frame)).get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
            assertThat(client.nextFrame(NO_MESSAGE_WAIT)).isNull();
        }
    }

    @Test
    @DisplayName("형식이 틀린 메시지는 버리고, 그 뒤 메시지는 그대로 받는다")
    void malformedMessages_droppedWithoutBreakingRelay() throws Exception {
        try (RawStompClient client = subscribed(this.universityId)) {
            awaitSubscribed(client, this.universityId);
            String channel = RedisKey.CHAT_UNIV_CHANNEL.of(this.universityId);

            publishRaw(channel, "{깨진 JSON");
            // 채널은 이 학교인데 본문의 방이 다른 학교
            publishRaw(channel, this.jsonMapper.writeValueAsString(message(this.universityId + 1, "방이 다름")));
            this.publisher.publishToUniversity(this.universityId, message(this.universityId, "정상"));

            assertThat(nextContent(client)).isEqualTo("정상");
            assertThat(client.nextFrame(NO_MESSAGE_WAIT)).isNull();
        }
    }
}
