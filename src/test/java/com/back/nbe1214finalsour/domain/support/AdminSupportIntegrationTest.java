package com.back.nbe1214finalsour.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerRequest;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.event.SupportAnsweredEvent;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.domain.support.service.AdminSupportService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 문의 목록·상세·답변 {@code GET /api/v1/admin/supports}, {@code GET /api/v1/admin/supports/{id}},
 * {@code POST /api/v1/admin/supports/{id}/answer} (추가 명세 §3-9, SCRUM-112·SCRUM-173)를 PostgreSQL(Testcontainers)과 실제 HTTP 처리로 확인한다.
 *
 * <p>관리자 목록은 테이블 전체를 보므로, 넣는 행의 {@code created_at}을 먼 미래로 바꿔 맨 앞에 오게 하고
 * {@code totalCount}는 DB에서 센 값과 비교한다. 넣은 행은 제목 접두어 {@link #TITLE_PREFIX}로 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdminSupportIntegrationTest {

    private static final String TITLE_PREFIX = "SCRUM-112 ";
    private static final String ANSWERS = "/api/v1/admin/supports/{id}/answer";
    private static final String DETAIL = "/api/v1/admin/supports/{id}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CustomerSupportRepository repository;
    @Autowired
    private AdminSupportService adminSupportService;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private ApplicationEvents events;

    private long universityId;
    private long memberId;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('문의관리테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        memberId = jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, '문의관리회원', 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, "e".repeat(64));
        adminToken = jwtProvider.createAccessToken(new AuthUser(1L, universityId, departmentId, Role.ADMIN), null);
        userToken = jwtProvider.createAccessToken(new AuthUser(memberId, universityId, departmentId, Role.USER), null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM customer_support WHERE title LIKE ?", TITLE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("전체 목록: 최신순·같은 시각 id 역순, 수신 이메일은 복호화한 원문, isMember는 회원 여부")
    void listsAllNewestFirst() throws Exception {
        long guest = insert(null, "guest@example.com", "비회원 문의", "2099-01-01 05:00:00+00");
        long memberLow = insert(memberId, "member@example.com", "회원 문의 1", "2099-01-01 06:00:00+00");
        long memberHigh = insert(memberId, "member@example.com", "회원 문의 2", "2099-01-01 06:00:00+00");
        answerByJdbc(memberHigh);
        long total = jdbcTemplate.queryForObject("SELECT count(*) FROM customer_support", Long.class);

        list(adminToken, null, 0, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].supportId").value(memberHigh))
                .andExpect(jsonPath("$.data.items[0].title").value(TITLE_PREFIX + "회원 문의 2"))
                .andExpect(jsonPath("$.data.items[0].recipientEmail").value("member@example.com"))
                .andExpect(jsonPath("$.data.items[0].isMember").value(true))
                .andExpect(jsonPath("$.data.items[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2099-01-01T15:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[1].supportId").value(memberLow))
                .andExpect(jsonPath("$.data.items[1].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.items[2].supportId").value(guest))
                .andExpect(jsonPath("$.data.items[2].recipientEmail").value("guest@example.com"))
                .andExpect(jsonPath("$.data.items[2].isMember").value(false))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.totalCount").value(total))
                .andExpect(jsonPath("$.data.hasNext").value(total > 3));
    }

    @Test
    @DisplayName("status로 거르면 그 상태만, 그 밖의 값과 size 범위 밖은 400 COMMON_INVALID_INPUT (계획 D6)")
    void filtersByStatus() throws Exception {
        long received = insert(memberId, "member@example.com", "접수", "2099-01-01 05:00:00+00");
        long answered = insert(memberId, "member@example.com", "답변", "2099-01-01 06:00:00+00");
        answerByJdbc(answered);

        list(adminToken, "RECEIVED", 0, 1)
                .andExpect(jsonPath("$.data.items[0].supportId").value(received))
                .andExpect(jsonPath("$.data.totalCount").value(countByStatus("RECEIVED")));
        list(adminToken, "ANSWERED", 0, 1)
                .andExpect(jsonPath("$.data.items[0].supportId").value(answered))
                .andExpect(jsonPath("$.data.totalCount").value(countByStatus("ANSWERED")));

        list(adminToken, "DONE", 0, 20)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        list(adminToken, null, 0, 101).andExpect(status().isBadRequest());
        list(adminToken, null, -1, 20).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("답변하면 200 ANSWERED, 답변이 저장되고 SupportAnsweredEvent가 한 번 발행된다")
    void answers() throws Exception {
        long id = insert(memberId, "member@example.com", "답변 대상", "2099-01-01 05:00:00+00");

        answer(adminToken, id, "문의 답변드립니다", "확인 결과 ...")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportId").value(id))
                .andExpect(jsonPath("$.data.status").value("ANSWERED"));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("ANSWERED");
        assertThat(row.get("answer_title")).isEqualTo("문의 답변드립니다");
        assertThat(row.get("answer_content")).isEqualTo("확인 결과 ...");
        assertThat(events.stream(SupportAnsweredEvent.class)).containsExactly(new SupportAnsweredEvent(id));
    }

    @Test
    @DisplayName("상세: 다른 회원·비회원 문의도 본문·수신 이메일을 보고, 답변 전 answer는 null, 답변 후 상태와 답변이 바뀐다 (SCRUM-173)")
    void detailShowsContentAndAnswer() throws Exception {
        long guest = insert(null, "guest@example.com", "비회원 상세", "2099-01-01 05:00:00+00");
        jdbcTemplate.update("UPDATE customer_support SET attach_file_url = 'https://cdn.example.com/a.png' WHERE id = ?",
                guest);

        detail(adminToken, guest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportId").value(guest))
                .andExpect(jsonPath("$.data.title").value(TITLE_PREFIX + "비회원 상세"))
                .andExpect(jsonPath("$.data.content").value("내용입니다"))
                .andExpect(jsonPath("$.data.attachFileUrl").value("https://cdn.example.com/a.png"))
                .andExpect(jsonPath("$.data.recipientEmail").value("guest@example.com"))
                .andExpect(jsonPath("$.data.isMember").value(false))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.answer").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").value("2099-01-01T14:00:00+09:00"));

        answer(adminToken, guest, "답변 제목", "답변 내용").andExpect(status().isOk());

        detail(adminToken, guest)
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer.title").value("답변 제목"))
                .andExpect(jsonPath("$.data.answer.content").value("답변 내용"));
    }

    @Test
    @DisplayName("상세: 없는 문의는 404 SUPPORT_NOT_FOUND, 익명은 401, 일반 회원은 본인 문의여도 403")
    void detailErrors() throws Exception {
        long id = insert(memberId, "member@example.com", "상세 권한", "2099-01-01 05:00:00+00");

        mockMvc.perform(get(DETAIL, id)).andExpect(status().isUnauthorized());
        detail(userToken, id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        jdbcTemplate.update("DELETE FROM customer_support WHERE id = ?", id);
        detail(adminToken, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("이미 답변한 문의는 409 SUPPORT_ALREADY_ANSWERED이고 첫 답변이 남으며 이벤트도 없다")
    void secondAnswerConflicts() throws Exception {
        long id = insert(memberId, "member@example.com", "두 번 답변", "2099-01-01 05:00:00+00");
        answer(adminToken, id, "첫 답변", "첫 내용").andExpect(status().isOk());

        answer(adminToken, id, "두 번째 답변", "두 번째 내용")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPORT_ALREADY_ANSWERED"));

        assertThat(row(id).get("answer_title")).isEqualTo("첫 답변");
        assertThat(events.stream(SupportAnsweredEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("없는 문의는 404 SUPPORT_NOT_FOUND이고 이벤트가 없다")
    void answerNotFound() throws Exception {
        long id = insert(memberId, "member@example.com", "지울 문의", "2099-01-01 05:00:00+00");
        jdbcTemplate.update("DELETE FROM customer_support WHERE id = ?", id);

        answer(adminToken, id, "답변", "내용")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPORT_NOT_FOUND"));
        assertThat(events.stream(SupportAnsweredEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("답변 제목이 비었거나 100자를 넘거나, 내용이 5,000자를 넘으면 400이고 바뀌지 않는다 (계획 D2)")
    void validatesAnswer() throws Exception {
        long id = insert(memberId, "member@example.com", "검증", "2099-01-01 05:00:00+00");

        answer(adminToken, id, " ", "내용").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        answer(adminToken, id, "가".repeat(101), "내용").andExpect(status().isBadRequest());
        answer(adminToken, id, "제목", "가".repeat(5001)).andExpect(status().isBadRequest());
        answer(adminToken, id, "가".repeat(100), "가".repeat(5000)).andExpect(status().isOk());

        assertThat(events.stream(SupportAnsweredEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("익명은 401, 일반 회원은 403 COMMON_FORBIDDEN (목록·답변 모두)")
    void adminOnly() throws Exception {
        long id = insert(memberId, "member@example.com", "권한", "2099-01-01 05:00:00+00");

        mockMvc.perform(get("/api/v1/admin/supports")).andExpect(status().isUnauthorized());
        list(userToken, null, 0, 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(post(ANSWERS, id).contentType(MediaType.APPLICATION_JSON)
                        .content(answerBody("답변", "내용")))
                .andExpect(status().isUnauthorized());
        answer(userToken, id, "답변", "내용").andExpect(status().isForbidden());

        assertThat(row(id).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("두 관리자가 동시에 답변하면 한 건만 성공하고 나머지는 409, 저장된 답변은 성공한 쪽이다 (계획 Q5)")
    void concurrentAnswersOnlyOneWins() throws Exception {
        long id = insert(memberId, "member@example.com", "동시 답변", "2099-01-01 05:00:00+00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (String title : List.of("관리자 A 답변", "관리자 B 답변")) {
                Callable<String> task = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        adminSupportService.answer(1L, id, new SupportAnswerRequest(title, "내용"));
                        return title;
                    } catch (BusinessException e) {
                        return e.getErrorCode().name();
                    }
                };
                results.add(pool.submit(task));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(10, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce(ErrorCode.SUPPORT_ALREADY_ANSWERED.name());
            String winner = outcomes.stream()
                    .filter(outcome -> !outcome.equals(ErrorCode.SUPPORT_ALREADY_ANSWERED.name()))
                    .findFirst().orElseThrow();
            assertThat(row(id).get("answer_title")).isEqualTo(winner);
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions list(String token, @Nullable String status, int page, int size) throws Exception {
        var builder = get("/api/v1/admin/supports")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (status != null) {
            builder.param("status", status);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions detail(String token, long id) throws Exception {
        return mockMvc.perform(get(DETAIL, id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions answer(String token, long id, String title, String content) throws Exception {
        return mockMvc.perform(post(ANSWERS, id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(answerBody(title, content)));
    }

    private String answerBody(String title, String content) {
        return jsonMapper.writeValueAsString(Map.of("answerTitle", title, "answerContent", content));
    }

    /** 저장소로 넣어 수신 이메일을 암호문으로 저장한 뒤, 정렬 검사를 위해 {@code created_at}만 바꾼다 */
    private long insert(@Nullable Long userId, String email, String title, String createdAt) {
        long id = repository.save(CustomerSupport.receive(userId, email, TITLE_PREFIX + title, "내용입니다", null))
                .getId();
        jdbcTemplate.update("UPDATE customer_support SET created_at = ?::timestamptz WHERE id = ?", createdAt, id);
        return id;
    }

    private void answerByJdbc(long id) {
        jdbcTemplate.update("UPDATE customer_support SET status = 'ANSWERED', answer_title = '답변', "
                + "answer_content = '내용' WHERE id = ?", id);
    }

    private long countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM customer_support WHERE status = ?", Long.class,
                status);
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM customer_support WHERE id = ?", id);
    }
}
