package com.back.nbe1214finalsour.domain.support.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.entity.SupportStatus;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CustomerSupport}가 테이블에 맞게 저장·조회되고, 수신 이메일이 암호문으로 저장되며, 조건부 답변 UPDATE가
 * 접수 상태에서만 한 번 성공하는지 PostgreSQL로 확인한다 (SCRUM-109). 넣은 데이터는 테스트 트랜잭션이 끝나면 롤백된다.
 */
@IntegrationTest
@Transactional
class CustomerSupportRepositoryTest {

    private static final String EMAIL = "Me@Example.com";

    @Autowired
    private CustomerSupportRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("회원 문의를 저장하면 수신 이메일은 암호문으로 들어가고 엔티티로는 원문이 읽힌다")
    void savesMemberSupportWithEncryptedEmail() {
        long userId = insertUser();

        Long id = repository.saveAndFlush(CustomerSupport.receive(
                userId, EMAIL, "아이템 효과가 적용되지 않아요", "2배 클릭을 샀는데 점수가 그대로입니다.", null)).getId();
        entityManager.clear();

        String stored = jdbcTemplate.queryForObject(
                "SELECT recipient_email FROM customer_support WHERE id = ?", String.class, id);
        assertThat(stored).isNotEqualTo(EMAIL).doesNotContainIgnoringCase("example");

        CustomerSupport found = repository.findById(id).orElseThrow();
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.isMember()).isTrue();
        assertThat(found.getRecipientEmail()).isEqualTo(EMAIL);
        assertThat(found.getStatus()).isEqualTo(SupportStatus.RECEIVED);
        assertThat(found.getAnswerTitle()).isNull();
        assertThat(found.getAnswerContent()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("비회원 문의는 user_id 없이 저장된다")
    void savesGuestSupport() {
        Long id = repository.saveAndFlush(CustomerSupport.receive(null, EMAIL, "제목", "내용", null)).getId();
        entityManager.clear();

        CustomerSupport found = repository.findById(id).orElseThrow();
        assertThat(found.getUserId()).isNull();
        assertThat(found.isMember()).isFalse();
    }

    @Test
    @DisplayName("254자 이메일의 암호문도 recipient_email 열에 들어간다 (V8)")
    void storesLongestEmail() {
        String longest = "a".repeat(64) + "@" + "b".repeat(185) + ".com";
        assertThat(longest).hasSize(254);

        Long id = repository.saveAndFlush(CustomerSupport.receive(null, longest, "제목", "내용", null)).getId();
        entityManager.clear();

        assertThat(repository.findById(id).orElseThrow().getRecipientEmail()).isEqualTo(longest);
    }

    @Test
    @DisplayName("답변은 접수 상태에서만 한 번 들어가고, 두 번째 시도와 없는 문의는 0행이다")
    void answersOnlyOnce() {
        Long id = repository.saveAndFlush(CustomerSupport.receive(null, EMAIL, "제목", "내용", null)).getId();

        assertThat(repository.answer(id, "문의 답변드립니다", "확인 결과 ...")).isEqualTo(1);
        assertThat(repository.answer(id, "다른 답변", "다른 내용")).isZero();
        assertThat(repository.answer(Long.MAX_VALUE, "제목", "내용")).isZero();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, answer_title, answer_content FROM customer_support WHERE id = ?", id);
        assertThat(row).containsEntry("status", "ANSWERED")
                .containsEntry("answer_title", "문의 답변드립니다")
                .containsEntry("answer_content", "확인 결과 ...");

        CustomerSupport found = repository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SupportStatus.ANSWERED);
        assertThat(found.getAnswerTitle()).isEqualTo("문의 답변드립니다");
    }

    private long insertUser() {
        long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('문의테스트대') RETURNING id", Long.class);
        long departmentId = jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at) "
                        + "VALUES (?, ?, '문의테스트', 'enc', ?, 'pw', now()) RETURNING id",
                Long.class, universityId, departmentId, "e".repeat(64));
    }
}
