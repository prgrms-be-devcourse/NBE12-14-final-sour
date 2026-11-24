package com.back.nbe1214finalsour.domain.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.ChatRecipientFixture;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProperties;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * STOMP 연결 인증과 구독 권한 (SCRUM-128, 계획 D1·D14·D23·§3-1). 실제 서버에 WebSocket으로 붙어 프레임을 주고받는다.
 *
 * <p>학교 본문을 실제로 받는 테스트만 회원·학교 행을 만든다. 연결과 학교 구독의 토큰 판정은 별도로 검증한다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatStompIntegrationTest {

    /** application.yaml의 cors.allowed-origins 기본값 */
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private EmailHasher emailHasher;

    private long userId;
    private long universityId;

    // 테스트끼리 같은 Redis를 쓰므로 회원·학교 ID를 매번 새로 정한다
    @BeforeEach
    void setUp() {
        this.userId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        this.universityId = ThreadLocalRandom.current().nextLong(1, 1_000_000);
    }

    private String token() {
        return this.jwtProvider.createAccessToken(new AuthUser(this.userId, this.universityId, 34L, Role.USER), null);
    }

    private RawStompClient connected() throws Exception {
        RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN);
        client.connect(token());
        assertThat(command(client.requireFrame())).isEqualTo("CONNECTED");
        return client;
    }

    private static String command(String frame) {
        return frame.substring(0, frame.indexOf('\n'));
    }

    /** ERROR 프레임이 오고, 헤더 message와 본문 code가 같은 오류 코드이며, 서버가 연결을 끊는지 확인한다 */
    private static void assertRejected(RawStompClient client, String errorCode) throws Exception {
        String frame = client.requireFrame();
        assertThat(command(frame)).isEqualTo("ERROR");
        assertThat(frame).contains("\nmessage:" + errorCode + "\n");
        assertThat(frame).contains("\"code\":\"" + errorCode + "\"");
        assertThat(client.awaitClose(CLOSE_TIMEOUT)).isNotNull();
    }

    private String universityTopic(long id) {
        return ChatDestinations.university(id);
    }

    // ===== CONNECT =====

    @Test
    @DisplayName("토큰 없이 CONNECT하면 ERROR COMMON_UNAUTHORIZED이고 연결이 끊긴다")
    void connect_withoutToken() throws Exception {
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.connect(null);

            assertRejected(client, "COMMON_UNAUTHORIZED");
        }
    }

    @Test
    @DisplayName("위조된 토큰으로 CONNECT하면 ERROR COMMON_INVALID_TOKEN")
    void connect_invalidToken() throws Exception {
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.connect(token() + "x");

            assertRejected(client, "COMMON_INVALID_TOKEN");
        }
    }

    @Test
    @DisplayName("만료된 토큰으로 CONNECT하면 ERROR COMMON_EXPIRED_TOKEN (프론트가 재발급 후 다시 연결할 신호)")
    void connect_expiredToken() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = Jwts.builder()
                .subject(String.valueOf(this.userId)).claim("universityId", this.universityId).claim("role", "USER")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(Keys.hmacShaKeyFor(this.jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.connect(expired);

            assertRejected(client, "COMMON_EXPIRED_TOKEN");
        }
    }

    @Test
    @DisplayName("다른 기기에서 로그인해 바뀐 세션의 토큰이면 ERROR AUTH_SESSION_REPLACED (HTTP와 같은 규칙)")
    void connect_replacedSession() throws Exception {
        this.loginSessionStore.put(this.userId, UUID.randomUUID().toString());
        String oldDevice = this.jwtProvider.createAccessToken(
                new AuthUser(this.userId, this.universityId, 34L, Role.USER), UUID.randomUUID().toString());
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.connect(oldDevice);

            assertRejected(client, "AUTH_SESSION_REPLACED");
        }
    }

    @Test
    @DisplayName("유효한 토큰이면 CONNECTED이고 heartbeat는 10초다")
    void connect_validToken() throws Exception {
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.connect(token());

            String frame = client.requireFrame();
            assertThat(command(frame)).isEqualTo("CONNECTED");
            assertThat(frame).contains("\nheart-beat:10000,10000\n");
            assertThat(frame).contains("\nuser-name:" + this.userId + "\n");
        }
    }

    @Test
    @DisplayName("허용하지 않은 origin은 핸드셰이크에서 거부된다")
    void handshake_disallowedOrigin() {
        assertThatThrownBy(() -> RawStompClient.open(this.port, "https://evil.example"))
                .hasStackTraceContaining("403");
    }

    @Test
    @DisplayName("CONNECT 없이 보낸 SUBSCRIBE는 ERROR COMMON_UNAUTHORIZED이고 연결이 끊긴다 (서버 오류로 답하지 않는다)")
    void subscribe_beforeConnect() throws Exception {
        try (RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN)) {
            client.subscribe("sub-0", universityTopic(this.universityId));

            assertRejected(client, "COMMON_UNAUTHORIZED");
        }
    }

    // ===== SUBSCRIBE =====

    @Test
    @DisplayName("본인 학교 방을 구독하면 그 방에 발행된 메시지를 받는다")
    void subscribe_ownUniversity_receivesMessages() throws Exception {
        ChatRecipientFixture recipient = ChatRecipientFixture.create(this.jdbc, this.users, this.emailHasher);
        this.userId = recipient.userId();
        this.universityId = recipient.universityId();
        try (RawStompClient client = connected()) {
            client.subscribe("sub-0", universityTopic(this.universityId));

            // SimpleBroker는 SUBSCRIBE에 RECEIPT를 주지 않으므로, 구독이 등록될 때까지 발행을 되풀이한다
            String frame = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (frame == null && System.nanoTime() < deadline) {
                this.messagingTemplate.convertAndSend(universityTopic(this.universityId), (Object) Map.of("content", "안녕"));
                frame = client.nextFrame(Duration.ofMillis(200));
            }

            assertThat(frame).isNotNull();
            assertThat(command(frame)).isEqualTo("MESSAGE");
            assertThat(frame).contains("\ndestination:" + universityTopic(this.universityId) + "\n");
            assertThat(frame).contains("\"content\":\"안녕\"");
        } finally {
            recipient.remove(this.jdbc);
        }
    }

    @Test
    @DisplayName("내 에러 큐(/user/queue/errors)는 구독할 수 있다")
    void subscribe_errorQueue() throws Exception {
        try (RawStompClient client = connected()) {
            client.subscribe("sub-0", ChatDestinations.ERROR_QUEUE);

            assertThat(client.nextFrame(Duration.ofMillis(500))).isNull();
            assertThat(client.awaitClose(Duration.ofMillis(100))).isNull();
        }
    }

    @Test
    @DisplayName("다른 학교 방을 구독하면 ERROR CHAT_NOT_MEMBER이고 연결이 끊긴다")
    void subscribe_otherUniversity() throws Exception {
        try (RawStompClient client = connected()) {
            client.subscribe("sub-0", universityTopic(this.universityId + 1));

            assertRejected(client, "CHAT_NOT_MEMBER");
        }
    }

    @Test
    @DisplayName("소속 연합이 없는 학교 회원이 연합 방을 구독하면 ERROR CHAT_NOT_MEMBER (연합 방 소속 판정은 AllianceChatIntegrationTest)")
    void subscribe_allianceWithoutMembership() throws Exception {
        try (RawStompClient client = connected()) {
            client.subscribe("sub-0", ChatDestinations.alliance(1));

            assertRejected(client, "CHAT_NOT_MEMBER");
        }
    }

    @Test
    @DisplayName("허용 목록에 없는 목적지(브로커 큐 직접 구독, 숫자가 아닌 방 ID)는 ERROR COMMON_FORBIDDEN")
    void subscribe_unlistedDestination() throws Exception {
        for (String destination : new String[] {"/queue/errors", "/topic/chat/universities/abc", "/topic/chat/alliances/abc"}) {
            try (RawStompClient client = connected()) {
                client.subscribe("sub-0", destination);

                assertRejected(client, "COMMON_FORBIDDEN");
            }
        }
    }

    // ===== SEND =====

    @Test
    @DisplayName("브로커 목적지(/topic)에 직접 SEND하면 ERROR COMMON_FORBIDDEN (저장·검사 없이 방에 뿌리는 것을 막는다)")
    void send_directlyToTopic() throws Exception {
        try (RawStompClient client = connected()) {
            client.send("SEND", "destination:" + universityTopic(this.universityId),
                    "content-type:application/json", "{\"content\":\"우회\"}");

            assertRejected(client, "COMMON_FORBIDDEN");
        }
    }

    // ===== 전송 한도 =====

    @Test
    @DisplayName("8KB를 넘는 프레임을 보내면 ERROR COMMON_INVALID_INPUT이고 연결이 끊긴다 (chat.websocket.message-size-limit)")
    void send_tooLargeFrame() throws Exception {
        try (RawStompClient client = connected()) {
            // 한글 3000자는 UTF-8로 9000바이트다
            client.send("SEND", "destination:/app/chat/university", "content-type:application/json",
                    "{\"content\":\"" + "가".repeat(3000) + "\"}");

            assertRejected(client, "COMMON_INVALID_INPUT");
        }
    }
}
