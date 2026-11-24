package com.back.nbe1214finalsour.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실제 PostgreSQL 제약 위반이 JPA 저장소 → 예외 변환 → {@link GlobalExceptionHandler}를 거쳐
 * API 명세서 §1 표의 코드로 응답되는지 확인한다.
 * Hibernate가 PostgreSQL 오류에서 제약 이름을 꺼내는지(특히 부분 유니크 인덱스)를 여기서 확인한다.
 *
 * <p>요청은 테스트 트랜잭션 안에서 처리되므로 넣은 데이터는 테스트가 끝나면 롤백된다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@Import({DbConstraintErrorIntegrationTest.SqlController.class, DbConstraintErrorIntegrationTest.SqlRepository.class})
class DbConstraintErrorIntegrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long universityA;
    private long departmentA;
    private long departmentB;

    @BeforeEach
    void setUp() {
        universityA = insertReturningId("INSERT INTO university (name) VALUES ('제약테스트대A') RETURNING id");
        long universityB = insertReturningId("INSERT INTO university (name) VALUES ('제약테스트대B') RETURNING id");
        departmentA = insertReturningId(
                "INSERT INTO department (university_id, name) VALUES (" + universityA + ", '컴퓨터공학과') RETURNING id");
        departmentB = insertReturningId(
                "INSERT INTO department (university_id, name) VALUES (" + universityB + ", '컴퓨터공학과') RETURNING id");
        jdbcTemplate.update(insertUserSql(departmentA, "Taken", HASH_A));
    }

    @Test
    @DisplayName("부분 유니크 인덱스 uk_users_nickname_ci 위반 → 409 USER_NICKNAME_DUPLICATED")
    void nicknameDuplicated() throws Exception {
        execute(insertUserSql(departmentA, "TAKEN", HASH_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_NICKNAME_DUPLICATED"))
                .andExpect(content().string(not(containsString("uk_users_nickname_ci"))));
    }

    @Test
    @DisplayName("외래키 fk_users_department 위반 → 400 USER_DEPARTMENT_MISMATCH")
    void departmentMismatch() throws Exception {
        execute(insertUserSql(departmentB, "다른학교학과", HASH_B))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_DEPARTMENT_MISMATCH"));
    }

    @Test
    @DisplayName("표에 없는 uk_university_name 위반 → 500 COMMON_INTERNAL_ERROR, SQL 정보 미노출")
    void unmappedConstraint() throws Exception {
        execute("INSERT INTO university (name) VALUES ('제약테스트대A')")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("uk_university_name"))))
                .andExpect(content().string(not(containsString("INSERT"))));
    }

    private ResultActions execute(String sql) throws Exception {
        return mockMvc.perform(post("/api/test/sql")
                .with(user("tester"))   // /api/** 는 로그인이 필요하다 (SecurityConfig)
                .contentType(MediaType.TEXT_PLAIN).content(sql));
    }

    private long insertReturningId(String sql) {
        Long id = jdbcTemplate.queryForObject(sql, Long.class);
        return id == null ? -1 : id;
    }

    private String insertUserSql(long departmentId, String nickname, String emailHash) {
        return "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                + "VALUES (" + universityA + ", " + departmentId + ", '" + nickname + "', 'enc', '" + emailHash
                + "', 'pw', now())";
    }

    /** 팀원 도메인의 Spring Data JPA 저장소처럼, 예외 변환이 적용되는 저장소에서 SQL을 실행한다. */
    @Repository
    static class SqlRepository {

        @PersistenceContext
        private EntityManager entityManager;

        public void execute(String sql) {
            entityManager.createNativeQuery(sql).executeUpdate();
        }
    }

    @RestController
    static class SqlController {

        private final SqlRepository repository;

        SqlController(SqlRepository repository) {
            this.repository = repository;
        }

        @PostMapping("/api/test/sql")
        void execute(@RequestBody String sql) {
            repository.execute(sql);
        }
    }
}
