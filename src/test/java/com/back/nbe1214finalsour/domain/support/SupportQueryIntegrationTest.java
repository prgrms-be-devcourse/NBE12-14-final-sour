package com.back.nbe1214finalsour.domain.support;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 내 문의 목록·상세 {@code GET /api/v1/supports/me}, {@code GET /api/v1/supports/{supportId}} (추가 명세 §3-8,
 * SCRUM-111)를 PostgreSQL(Testcontainers)과 실제 HTTP 처리로 확인한다.
 *
 * <p>문의는 저장소로 넣어 수신 이메일이 암호문으로 저장되게 하고, 정렬을 보기 위해 {@code created_at}만 JDBC로 바꾼다.
 * 넣은 행은 제목 접두어 {@link #TITLE_PREFIX}로 지운다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SupportQueryIntegrationTest {

    private static final String TITLE_PREFIX = "SCRUM-111 ";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CustomerSupportRepository repository;
    @Autowired
    private JwtProvider jwtProvider;

    private long universityId;
    private long memberId;
    private long otherMemberId;
    private String memberToken;
    private String otherMemberToken;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('문의조회테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id", Long.class,
                universityId);
        memberId = insertUser(departmentId, "조회회원", "c");
        otherMemberId = insertUser(departmentId, "다른조회회원", "d");
        memberToken = jwtProvider.createAccessToken(new AuthUser(memberId, universityId, departmentId, Role.USER), null);
        otherMemberToken = jwtProvider.createAccessToken(
                new AuthUser(otherMemberId, universityId, departmentId, Role.USER), null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM customer_support WHERE title LIKE ?", TITLE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM users WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM department WHERE university_id = ?", universityId);
        jdbcTemplate.update("DELETE FROM university WHERE id = ?", universityId);
    }

    @Test
    @DisplayName("내 목록은 내 문의만 최신순으로, 같은 시각이면 id 역순으로 나누어 준다 (계획 D5)")
    void listsMineNewestFirst() throws Exception {
        long oldest = insert(memberId, "가장 오래됨", "2026-11-24 04:00:00+00");
        long tieLow = insert(memberId, "같은 시각 1", "2026-11-24 05:00:00+00");
        long tieHigh = insert(memberId, "같은 시각 2", "2026-11-24 05:00:00+00");
        insert(otherMemberId, "다른 회원 문의", "2026-11-24 06:00:00+00");
        insert(null, "비회원 문의", "2026-11-24 06:00:00+00");

        getMine(memberToken, 0, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].supportId").value(tieHigh))
                .andExpect(jsonPath("$.data.items[0].title").value(TITLE_PREFIX + "같은 시각 2"))
                .andExpect(jsonPath("$.data.items[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-24T14:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[1].supportId").value(tieLow))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        getMine(memberToken, 1, 2)
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].supportId").value(oldest))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("목록 응답에 본문·이메일·답변은 없고, 답변한 문의는 ANSWERED로 보인다")
    void listItemShape() throws Exception {
        long id = insert(memberId, "답변된 문의", "2026-11-24 05:00:00+00");
        answer(id);

        getMine(memberToken, 0, 20)
                .andExpect(jsonPath("$.data.items[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].recipientEmail").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].answer").doesNotExist());
    }

    @Test
    @DisplayName("문의가 없으면 빈 목록, size는 1~100, page는 0 이상 (아니면 400)")
    void emptyListAndPagingBounds() throws Exception {
        getMine(memberToken, 0, 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        getMine(memberToken, 0, 100).andExpect(status().isOk());
        getMine(memberToken, 0, 101).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        getMine(memberToken, 0, 0).andExpect(status().isBadRequest());
        getMine(memberToken, -1, 20).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("답변 전 상세는 answer가 null이고, 수신 이메일은 내보내지 않는다")
    void detailBeforeAnswer() throws Exception {
        String url = "https://cdn.devcproject.cloud/support/" + memberId + "/a1b2.png";
        long id = repository.save(CustomerSupport.receive(memberId, "me@example.com", TITLE_PREFIX + "첨부 문의",
                "2배 클릭을 샀는데 점수가 그대로입니다.", url)).getId();

        getDetail(memberToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supportId").value(id))
                .andExpect(jsonPath("$.data.title").value(TITLE_PREFIX + "첨부 문의"))
                .andExpect(jsonPath("$.data.content").value("2배 클릭을 샀는데 점수가 그대로입니다."))
                .andExpect(jsonPath("$.data.attachFileUrl").value(url))
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.answer").value(nullValue()))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.recipientEmail").doesNotExist());
    }

    @Test
    @DisplayName("답변 후 상세는 answer에 제목과 내용이 있다")
    void detailAfterAnswer() throws Exception {
        long id = insert(memberId, "답변 받을 문의", "2026-11-24 05:00:00+00");
        answer(id);

        getDetail(memberToken, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"))
                .andExpect(jsonPath("$.data.answer.title").value("문의 답변드립니다"))
                .andExpect(jsonPath("$.data.answer.content").value("확인 결과 ..."))
                .andExpect(jsonPath("$.data.attachFileUrl").value(nullValue()));
    }

    @Test
    @DisplayName("다른 회원의 문의와 비회원 문의는 403 SUPPORT_NOT_OWNER")
    void detailOfOthersIsForbidden() throws Exception {
        long others = insert(otherMemberId, "다른 회원 문의", "2026-11-24 05:00:00+00");
        long guest = insert(null, "비회원 문의", "2026-11-24 05:00:00+00");

        getDetail(memberToken, others)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPPORT_NOT_OWNER"));
        getDetail(memberToken, guest)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUPPORT_NOT_OWNER"));
        getDetail(otherMemberToken, others).andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 문의는 404 SUPPORT_NOT_FOUND")
    void detailNotFound() throws Exception {
        long id = insert(memberId, "지울 문의", "2026-11-24 05:00:00+00");
        jdbcTemplate.update("DELETE FROM customer_support WHERE id = ?", id);

        getDetail(memberToken, id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("토큰이 없으면 목록·상세 모두 401")
    void requiresLogin() throws Exception {
        long id = insert(memberId, "로그인 필요", "2026-11-24 05:00:00+00");

        mockMvc.perform(get("/api/v1/supports/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/supports/{id}", id)).andExpect(status().isUnauthorized());
    }

    private ResultActions getMine(String token, int page, int size) throws Exception {
        return mockMvc.perform(get("/api/v1/supports/me")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions getDetail(String token, long id) throws Exception {
        return mockMvc.perform(get("/api/v1/supports/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** 저장소로 넣어 수신 이메일을 암호문으로 저장한 뒤, 정렬 검사를 위해 {@code created_at}만 바꾼다 */
    private long insert(@Nullable Long userId, String title, String createdAt) {
        long id = repository.save(CustomerSupport.receive(userId, "me@example.com", TITLE_PREFIX + title,
                "내용입니다", null)).getId();
        jdbcTemplate.update("UPDATE customer_support SET created_at = ?::timestamptz WHERE id = ?", createdAt, id);
        return id;
    }

    private void answer(long id) {
        jdbcTemplate.update("UPDATE customer_support SET status = 'ANSWERED', answer_title = '문의 답변드립니다', "
                + "answer_content = '확인 결과 ...' WHERE id = ?", id);
    }

    private long insertUser(long departmentId, String nickname, String hashChar) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, ?, 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, nickname, hashChar.repeat(64));
    }
}
