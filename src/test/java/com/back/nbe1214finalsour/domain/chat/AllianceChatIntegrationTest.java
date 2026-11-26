package com.back.nbe1214finalsour.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연합 채팅 (SCRUM-182, 채팅 2단계). 실제 서버에 STOMP로 연결하고 Redis·PostgreSQL을 실제로 쓴다.
 * 학교 A·B는 같은 연합, 학교 C는 연합이 없다. 연합 행은 상태를 직접 바꾸려고 SQL로 만든다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AllianceChatIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String SEND_DESTINATION = "/app/chat/alliance";
    private static final Duration NO_MESSAGE_WAIT = Duration.ofMillis(700);

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;
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

    private final List<RawStompClient> clients = new ArrayList<>();
    private Member a;
    private Member b;
    private Member c;
    private long allianceId;
    private OffsetDateTime expiredAt;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        this.a = member("연합채팅A" + suffix);
        this.b = member("연합채팅B" + suffix);
        this.c = member("연합채팅C" + suffix);
        this.universityCache.load();

        this.expiredAt = OffsetDateTime.now().plusHours(24).withNano(0);
        this.allianceId = this.jdbcTemplate.queryForObject("""
                INSERT INTO alliance (name, duration_hours, expired_at) VALUES ('채팅연합', 24, ?) RETURNING id
                """, Long.class, this.expiredAt);
        this.jdbcTemplate.update("INSERT INTO alliance_chat_room (alliance_id, alliance_name) VALUES (?, '채팅연합')", this.allianceId);
        for (Member member : List.of(this.a, this.b)) {
            this.jdbcTemplate.update("""
                    INSERT INTO alliance_university (alliance_id, university_id, applicant_user_id, approver_user_id, status)
                    VALUES (?, ?, ?, ?, 'ACCEPTED')
                    """, this.allianceId, member.universityId(), member.user().getId(), this.a.user().getId());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (RawStompClient client : this.clients) {
            client.close();
        }
        this.jdbcTemplate.update("DELETE FROM chat_message WHERE alliance_chat_room_id = ?", this.allianceId);
        this.jdbcTemplate.update("DELETE FROM alliance_university WHERE alliance_id = ?", this.allianceId);
        this.jdbcTemplate.update("DELETE FROM alliance_chat_room WHERE alliance_id = ?", this.allianceId);
        this.jdbcTemplate.update("DELETE FROM alliance WHERE id = ?", this.allianceId);
        for (Member member : List.of(this.a, this.b, this.c)) {
            this.redis.delete(RedisKey.RATELIMIT_CHAT.of(member.user().getId()));
            this.jdbcTemplate.update("DELETE FROM users WHERE id = ?", member.user().getId());
            this.jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", member.universityId());
            this.jdbcTemplate.update("DELETE FROM university WHERE id = ?", member.universityId());
        }
        this.universityCache.load();
    }

    private Member member(String universityName) {
        long universityId = this.jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id", Long.class, universityName);
        Long departmentId = this.jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        String email = "alliance-chat-" + UUID.randomUUID() + "@chat-test.ac.kr";
        User user = this.userRepository.saveAndFlush(User.create(universityId, departmentId,
                "연합" + UUID.randomUUID().toString().substring(0, 8), email, this.emailHasher.hash(email), "{noop}unused",
                OffsetDateTime.now()));
        return new Member(user, universityId);
    }

    private String token(Member member) {
        return this.jwtProvider.createAccessToken(
                new AuthUser(member.user().getId(), member.universityId(), null, Role.USER), null);
    }

    private RawStompClient connect(Member member) throws Exception {
        RawStompClient client = RawStompClient.open(this.port, ALLOWED_ORIGIN);
        this.clients.add(client);
        client.connect(token(member));
        assertThat(client.requireFrame()).startsWith("CONNECTED\n");
        return client;
    }

    /** 연합 방과 에러 큐를 구독하고, 두 구독이 모두 등록될 때까지 기다린다 (SimpleBroker는 RECEIPT를 주지 않는다) */
    private RawStompClient joined(Member member) throws Exception {
        RawStompClient client = connect(member);
        client.subscribe("room", ChatDestinations.alliance(this.allianceId));
        client.subscribe("err", ChatDestinations.ERROR_QUEUE);
        boolean room = false;
        boolean errors = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!(room && errors) && System.nanoTime() < deadline) {
            this.messagingTemplate.convertAndSend(ChatDestinations.alliance(this.allianceId), (Object) Map.of("probe", true));
            this.messagingTemplate.convertAndSendToUser(String.valueOf(member.user().getId()), "/queue/errors",
                    (Object) Map.of("probe", true));
            for (String frame = client.nextFrame(Duration.ofMillis(200)); frame != null;
                 frame = client.nextFrame(Duration.ofMillis(100))) {
                assertThat(frame).doesNotStartWith("ERROR");
                room |= frame.contains("\nsubscription:room\n");
                errors |= frame.contains("\nsubscription:err\n");
            }
        }
        assertThat(room && errors).as("5초 안에 두 구독이 등록됨").isTrue();
        drain(client);
        return client;
    }

    private static void drain(RawStompClient client) throws Exception {
        while (client.nextFrame(Duration.ofMillis(300)) != null) {
            // 다른 구독자의 준비 메시지를 버린다
        }
    }

    private void send(RawStompClient client, String content) throws Exception {
        client.sendJson(SEND_DESTINATION, this.jsonMapper.writeValueAsString(Map.of("content", content)));
    }

    private JsonNode next(RawStompClient client, String subscription) throws Exception {
        String frame = client.requireFrame();
        assertThat(frame).startsWith("MESSAGE\n").contains("\nsubscription:" + subscription + "\n");
        return this.jsonMapper.readTree(frame.substring(frame.indexOf("\n\n") + 2));
    }

    private List<ChatMessage> stored() {
        return this.chatMessageRepository.findByAllianceChatRoomIdOrderByIdDesc(this.allianceId, org.springframework.data.domain.PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("같은 연합의 다른 학교 회원끼리 주고받는다")
    void membersOfDifferentUniversitiesExchangeMessages() throws Exception {
        RawStompClient fromA = joined(this.a);
        RawStompClient fromB = joined(this.b);
        drain(fromA);

        send(fromA, "  오늘 안에 연합 1등 가자 바보  ");

        for (RawStompClient client : List.of(fromA, fromB)) {
            JsonNode message = next(client, "room");
            assertThat(message.get("roomId").asString()).isEqualTo("ALLIANCE#" + this.allianceId);
            assertThat(message.get("userId").asLong()).isEqualTo(this.a.user().getId());
            assertThat(message.get("universityName").asString()).startsWith("연합채팅A");
            assertThat(message.get("content").asString()).isEqualTo("오늘 안에 연합 1등 가자 **");
        }
        assertThat(stored()).singleElement().satisfies(saved -> {
            assertThat(saved.getContent()).isEqualTo("오늘 안에 연합 1등 가자 **");
            assertThat(saved.getAllianceChatRoomId()).isEqualTo(this.allianceId);
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT last_content FROM alliance_chat_room WHERE alliance_id = ?", String.class, this.allianceId))
                    .isEqualTo("오늘 안에 연합 1등 가자 **");
            assertThat(this.jdbcTemplate.queryForObject(
                    "SELECT last_content_at FROM alliance_chat_room WHERE alliance_id = ?", OffsetDateTime.class, this.allianceId))
                    .isEqualTo(saved.getCreatedAt());
        });

        send(fromB, "좋아요");
        assertThat(next(fromA, "room").get("universityName").asString()).startsWith("연합채팅B");
        assertThat(next(fromB, "room").get("content").asString()).isEqualTo("좋아요");
    }

    @Test
    @DisplayName("연합에 속하지 않은 학교 회원: 구독은 ERROR CHAT_NOT_MEMBER, 전송은 에러 큐 CHAT_NOT_MEMBER이고 1초 한도를 쓰지 않는다")
    void nonMemberIsRejected() throws Exception {
        RawStompClient subscriber = connect(this.c);
        subscriber.subscribe("room", ChatDestinations.alliance(this.allianceId));
        String frame = subscriber.requireFrame();
        assertThat(frame).startsWith("ERROR\n").contains("\nmessage:CHAT_NOT_MEMBER\n");

        RawStompClient sender = connect(this.c);
        sender.subscribe("err", ChatDestinations.ERROR_QUEUE);
        drain(sender);
        send(sender, "끼어들기");
        assertThat(next(sender, "err").get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
        assertThat(this.redis.hasKey(RedisKey.RATELIMIT_CHAT.of(this.c.user().getId()))).isFalse();
        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("탈퇴(LEFT)한 학교 회원은 연결 중이어도 다음 전송부터 CHAT_NOT_MEMBER (보낼 때마다 소속을 다시 본다)")
    void leftUniversityCannotSend() throws Exception {
        RawStompClient fromB = joined(this.b);
        this.jdbcTemplate.update("UPDATE alliance_university SET status = 'LEFT' WHERE alliance_id = ? AND university_id = ?",
                this.allianceId, this.b.universityId());

        send(fromB, "탈퇴 뒤");

        assertThat(next(fromB, "err").get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
        assertThat(fromB.nextFrame(NO_MESSAGE_WAIT)).isNull();
        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("LEFT 이후 기존 구독은 새 본문을 받지 않고, 정상 상대와 같은 세션의 학교·오류 큐는 계속 받는다")
    void leftUniversityCannotReceive() throws Exception {
        RawStompClient fromA = joined(this.a);
        RawStompClient fromB = joined(this.b);
        RawStompClient otherB = connect(this.b); // 같은 회원이어도 안내는 기존 연합 구독 세션에만 간다.
        otherB.subscribe("err", ChatDestinations.ERROR_QUEUE);
        awaitErrorQueue(otherB, this.b);
        drain(fromB);
        fromB.subscribe("campus", ChatDestinations.university(this.b.universityId()));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String ready = null;
        while (ready == null && System.nanoTime() < deadline) {
            this.messagingTemplate.convertAndSend(ChatDestinations.university(this.b.universityId()), (Object) Map.of("probe", true));
            ready = fromB.nextFrame(Duration.ofMillis(200));
        }
        assertThat(ready).isNotNull().contains("\nsubscription:campus\n");
        drain(fromA);
        drain(fromB);
        this.jdbcTemplate.update("UPDATE alliance_university SET status = 'LEFT' WHERE alliance_id = ? AND university_id = ?",
                this.allianceId, this.b.universityId());

        send(fromA, "정상 상대에게만 전달");

        assertThat(next(fromA, "room").get("content").asString()).isEqualTo("정상 상대에게만 전달");
        assertThat(next(fromB, "err").get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
        assertThat(fromB.nextFrame(NO_MESSAGE_WAIT)).isNull();
        assertThat(otherB.nextFrame(NO_MESSAGE_WAIT)).isNull();
        this.messagingTemplate.convertAndSend(ChatDestinations.university(this.b.universityId()), (Object) Map.of("content", "학교는 유지"));
        assertThat(next(fromB, "campus").get("content").asString()).isEqualTo("학교는 유지");
        assertThat(stored()).hasSize(1);
    }

    private void awaitErrorQueue(RawStompClient client, Member member) throws Exception {
        String frame = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (frame == null && System.nanoTime() < deadline) {
            this.messagingTemplate.convertAndSendToUser(String.valueOf(member.user().getId()), "/queue/errors", "ready");
            frame = client.nextFrame(Duration.ofMillis(200));
        }
        assertThat(frame).isNotNull().contains("\nsubscription:err\n");
        drain(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspended", "deleted-membership", "deleted-alliance", "expired"})
    @DisplayName("상태 변경 뒤 열린 연합 구독에는 본문 대신 권한 오류만 전달한다")
    void existingSubscriptionRechecksAuthorization(String change) throws Exception {
        RawStompClient subscribed = joined(this.b);
        switch (change) {
            case "suspended" -> this.jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", this.b.user().getId());
            case "deleted-membership" -> this.jdbcTemplate.update(
                    "UPDATE alliance_university SET deleted_at = now() WHERE alliance_id = ? AND university_id = ?",
                    this.allianceId, this.b.universityId());
            case "deleted-alliance" -> this.jdbcTemplate.update("UPDATE alliance SET deleted_at = now() WHERE id = ?", this.allianceId);
            case "expired" -> this.jdbcTemplate.update("UPDATE alliance SET expired_at = now() WHERE id = ?", this.allianceId);
            default -> throw new AssertionError(change);
        }

        // 발행 쪽 권한과 무관하게 이미 구독한 소비자의 전송 처리가 차단되어야 한다.
        this.messagingTemplate.convertAndSend(ChatDestinations.alliance(this.allianceId), (Object) Map.of("content", "차단 대상"));

        assertThat(next(subscribed, "err").get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
        assertThat(subscribed.nextFrame(NO_MESSAGE_WAIT)).isNull();
    }

    @Test
    @DisplayName("만료 시각이 지난 연합(스케줄러가 돌기 전)에는 보낼 수 없다")
    void expiredAllianceCannotSend() throws Exception {
        RawStompClient fromA = joined(this.a);
        this.jdbcTemplate.update("UPDATE alliance SET created_at = now() - interval '1 day', "
                + "expired_at = now() - interval '1 second' WHERE id = ?", this.allianceId);

        send(fromA, "만료 뒤");

        assertThat(next(fromA, "err").get("code").asString()).isEqualTo("CHAT_NOT_MEMBER");
        assertThat(stored()).isEmpty();
    }

    @Test
    @DisplayName("이전 메시지는 연합 소속 학교 회원만 읽는다. 다른 연합 ID·비소속은 403 CHAT_NOT_MEMBER")
    void history() throws Exception {
        RawStompClient fromA = joined(this.a);
        send(fromA, "기록될 메시지");
        long messageId = next(fromA, "room").get("messageId").asLong();

        this.mockMvc.perform(get("/api/v1/chat/alliances/{id}/messages", this.allianceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(this.b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].messageId").value(messageId))
                .andExpect(jsonPath("$.data.items[0].roomId").value("ALLIANCE#" + this.allianceId))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        this.mockMvc.perform(get("/api/v1/chat/alliances/{id}/messages", this.allianceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(this.c)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_NOT_MEMBER"));
        this.mockMvc.perform(get("/api/v1/chat/alliances/{id}/messages", this.allianceId + 1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(this.a)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_NOT_MEMBER"));
    }

    private record Member(User user, long universityId) {
    }
}
