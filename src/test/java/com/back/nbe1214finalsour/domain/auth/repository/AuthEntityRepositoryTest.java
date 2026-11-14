package com.back.nbe1214finalsour.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.auth.entity.RefreshToken;
import com.back.nbe1214finalsour.domain.auth.entity.UniDomain;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UniDomain}·{@link RefreshToken}이 테이블에 맞게 읽히고 저장되는지 PostgreSQL로 확인한다.
 * 넣은 데이터는 테스트 트랜잭션이 끝나면 롤백된다.
 */
@IntegrationTest
@Transactional
class AuthEntityRepositoryTest {

    @Autowired
    private UniDomainRepository uniDomainRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long universityId;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('인증테스트대') RETURNING id", Long.class);
    }

    @Test
    @DisplayName("uni_domain 행을 UniDomain으로 읽는다")
    void readsUniDomain() {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO uni_domain (university_id, domain) VALUES (?, 'auth-test.ac.kr') RETURNING id",
                Long.class, universityId);

        UniDomain found = uniDomainRepository.findById(id).orElseThrow();

        assertThat(found.getUniversityId()).isEqualTo(universityId);
        assertThat(found.getDomain()).isEqualTo("auth-test.ac.kr");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("RefreshToken을 저장하고 다시 읽는다")
    void savesRefreshToken() {
        long userId = insertUser();
        String tokenHash = "c".repeat(64);
        OffsetDateTime expiredAt = OffsetDateTime.now().plusDays(30).truncatedTo(ChronoUnit.MICROS);
        UUID sessionId = UUID.randomUUID();

        Long id = refreshTokenRepository.saveAndFlush(RefreshToken.issue(userId, tokenHash, expiredAt, sessionId))
                .getId();
        entityManager.clear();

        RefreshToken found = refreshTokenRepository.findById(id).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getToken()).isEqualTo(tokenHash);
        assertThat(found.getExpiredAt().toInstant()).isEqualTo(expiredAt.toInstant());
        assertThat(found.getSessionId()).isEqualTo(sessionId);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    private long insertUser() {
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, '토큰테스트', 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, "d".repeat(64));
    }
}
