package com.back.nbe1214finalsour.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.Role;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link User}가 {@code users} 테이블에 맞게 저장되고, 이메일이 암호화되어 저장되는지 PostgreSQL로 확인한다.
 * 넣은 데이터는 테스트 트랜잭션이 끝나면 롤백된다.
 */
@IntegrationTest
@Transactional
class UserRepositoryTest {

    private static final String EMAIL = "student@snu.ac.kr";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long universityId;
    private long departmentId;

    @BeforeEach
    void setUp() {
        universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('회원테스트대') RETURNING id", Long.class);
        departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
    }

    @Test
    @DisplayName("저장하면 users.email 열에는 원문이 아닌 암호문이 들어간다")
    void storesEncryptedEmail() {
        User saved = saveUser();

        String stored = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, saved.getId());

        assertThat(stored).isNotEqualTo(EMAIL).doesNotContain("student").doesNotContain("snu");
    }

    @Test
    @DisplayName("다시 읽으면 이메일이 원문으로 복호화되고, 나머지 값도 그대로다")
    void readsDecryptedEmail() {
        Long id = saveUser().getId();
        entityManager.clear();

        User found = userRepository.findById(id).orElseThrow();

        assertThat(found.getEmail()).isEqualTo(EMAIL);
        assertThat(found.getEmailHash()).isEqualTo(emailHasher.hash(EMAIL));
        assertThat(found.getUniversityId()).isEqualTo(universityId);
        assertThat(found.getDepartmentId()).isEqualTo(departmentId);
        assertThat(found.getNickname()).isEqualTo("대대대짱");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("클리커 레벨과 점수는 DB 기본값(1, 0, 0)으로 저장되고 저장 직후 엔티티에도 채워진다")
    void databaseDefaults() {
        User saved = saveUser();

        assertThat(saved.getClickerId()).isEqualTo(1L);
        assertThat(saved.getTotalContribScore()).isZero();
        assertThat(saved.getTotalAttackScore()).isZero();
    }

    @Test
    @DisplayName("클리커 레벨과 점수는 JPA UPDATE에 넣지 않는다 (클리커 업그레이드·기여도 지갑이 JDBC로 바꾸는 값을 덮어쓰지 않도록)")
    void otherModulesOwnScoreColumns() {
        EntityPersister persister = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactoryImplementor.class)
                .getMappingMetamodel()
                .getEntityDescriptor(User.class);
        List<String> names = List.of(persister.getPropertyNames());
        boolean[] updatable = persister.getPropertyUpdateability();

        for (String property : List.of("clickerId", "totalContribScore", "totalAttackScore")) {
            assertThat(updatable[names.indexOf(property)])
                    .withFailMessage("User.%s가 JPA UPDATE에 들어갑니다. 다른 모듈이 JDBC로 바꾼 값을 덮어쓸 수 있습니다.", property)
                    .isFalse();
        }
    }

    private User saveUser() {
        User user = User.create(universityId, departmentId, "대대대짱", EMAIL, emailHasher.hash(EMAIL),
                "{bcrypt}encoded", OffsetDateTime.now());
        return userRepository.saveAndFlush(user);
    }
}
