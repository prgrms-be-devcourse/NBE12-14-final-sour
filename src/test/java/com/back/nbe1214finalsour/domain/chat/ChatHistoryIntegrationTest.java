package com.back.nbe1214finalsour.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 대학 채팅 이전 메시지 조회 API (SCRUM-131). PostgreSQL 저장소를 사용한다. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class ChatHistoryIntegrationTest {

    private static final int MESSAGE_COUNT = 45;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
    @Autowired
    private JsonMapper jsonMapper;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private EmailHasher hasher;
    private ChatRecipientFixture recipient;
    private long universityId;
    /** 저장한 messageId. 오래된 것부터 */
    private final List<String> savedIds = new ArrayList<>();

    // 테스트용 학교와 방을 생성하고 트랜잭션 롤백으로 정리한다.
    @BeforeEach
    void setUp() {
        this.recipient = ChatRecipientFixture.create(jdbc, users, hasher);
        this.universityId = recipient.universityId();
        jdbc.update("INSERT INTO chat_room (university_id, university_name) VALUES (?, '테스트대학교')", universityId);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            ChatMessage message = ChatMessage.toUniversity(universityId, recipient.userId(), "닉네임", "테스트대학교", "메시지-" + i);
            this.chatMessageRepository.save(message);
            this.savedIds.add(message.getId().toString());
        }
    }

    private String token(long universityId) {
        return this.jwtProvider.createAccessToken(new AuthUser(recipient.userId(), universityId, recipient.departmentId(), Role.USER), null);
    }

    private ResultActions request(@Nullable String cursor, @Nullable String size) throws Exception {
        MockHttpServletRequestBuilder builder = get("/api/v1/chat/universities/{id}/messages", this.universityId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(this.universityId));
        if (cursor != null) {
            builder.param("cursor", cursor);
        }
        if (size != null) {
            builder.param("size", size);
        }
        return this.mockMvc.perform(builder);
    }

    private JsonNode page(@Nullable String cursor) throws Exception {
        String body = request(cursor, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return this.jsonMapper.readTree(body).get("data");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("messageId").asString()));
        return ids;
    }

    @Test
    @DisplayName("cursor 없이 최신 20개, nextCursor로 그 이전 20개, 마지막 5개에서 hasNext=false로 끝난다")
    void pagesThroughAllMessages() throws Exception {
        List<String> newestFirst = new ArrayList<>(this.savedIds.reversed());

        JsonNode first = page(null);
        assertThat(ids(first)).containsExactlyElementsOf(newestFirst.subList(0, 20));
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(first.get("nextCursor").asString()).isEqualTo(newestFirst.get(19));
        JsonNode newest = first.get("items").get(0);
        assertThat(newest.get("roomId").asString()).isEqualTo("UNIV#" + this.universityId);
        assertThat(newest.get("content").asString()).isEqualTo("메시지-44");
        assertThat(newest.get("nickname").asString()).isEqualTo("닉네임");
        assertThat(newest.get("universityName").asString()).isEqualTo("테스트대학교");
        assertThat(newest.get("createdAt").asString()).endsWith("+09:00");

        JsonNode second = page(first.get("nextCursor").asString());
        assertThat(ids(second)).containsExactlyElementsOf(newestFirst.subList(20, 40));
        assertThat(second.get("hasNext").asBoolean()).isTrue();

        JsonNode last = page(second.get("nextCursor").asString());
        assertThat(ids(last)).containsExactlyElementsOf(newestFirst.subList(40, 45));
        assertThat(last.get("hasNext").asBoolean()).isFalse();
        assertThat(last.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("size를 주면 그 개수만 읽는다")
    void customSize() throws Exception {
        request(null, "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("남은 메시지가 size와 같으면 다음 페이지와 커서가 없다")
    void exactlySizeHasNoNext() throws Exception {
        request(savedIds.get(5), "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").hasJsonPath())
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("메시지가 없는 방은 빈 페이지와 hasNext=false를 반환한다")
    void emptyRoomHasNoNext() throws Exception {
        jdbc.update("DELETE FROM chat_message WHERE chat_room_id = ?", universityId);
        request(null, "5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").hasJsonPath())
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("size가 20을 넘거나 1보다 작으면 400 COMMON_INVALID_INPUT")
    void sizeOutOfRange() throws Exception {
        for (String size : List.of("21", "0")) {
            request(null, size)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("양의 정수가 아닌 cursor는 400 COMMON_INVALID_INPUT")
    void invalidCursor() throws Exception {
        for (String cursor : List.of("abc", "0", "-1")) {
            request(cursor, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("다른 학교 방은 403 CHAT_NOT_MEMBER")
    void otherUniversity() throws Exception {
        this.mockMvc.perform(get("/api/v1/chat/universities/{id}/messages", this.universityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(this.universityId + 1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHAT_NOT_MEMBER"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 COMMON_UNAUTHORIZED")
    void requiresLogin() throws Exception {
        this.mockMvc.perform(get("/api/v1/chat/universities/{id}/messages", this.universityId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }
}
