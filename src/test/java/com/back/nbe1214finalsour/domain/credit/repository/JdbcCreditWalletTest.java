package com.back.nbe1214finalsour.domain.credit.repository;


import com.back.nbe1214finalsour.IntegrationTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Transactional
public class JdbcCreditWalletTest {
    @Autowired
    private JdbcCreditWallet creditWallet;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;


    @BeforeEach
    void setUp() {
        // users는 학교·학과 FK가 필수라 먼저 만든다
        Long universityId = this.jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES ('크레딧테스트대') RETURNING id", Long.class);
        Long departmentId = this.jdbcTemplate.queryForObject(
                "INSERT INTO department (university_id, name) VALUES (?, '컴퓨터공학과') RETURNING id",
                Long.class, universityId);
        userId = this.jdbcTemplate.queryForObject("""
                INSERT INTO users (university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, '크레딧유저', 'credit@test.com', ?, 'password', now())
                RETURNING id
                """, Long.class, universityId, departmentId, "e".repeat(64));
    }

    private void setBalance(long balance) {
        this.jdbcTemplate.update("UPDATE users SET credit_balance = ? WHERE id = ?", balance, userId);
    }

    private long balanceInDb() {
        return this.jdbcTemplate.queryForObject("SELECT credit_balance FROM users WHERE id = ?", Long.class, userId);
    }


    @Test
    @DisplayName("현재 크레딧 잔액을 조회한다")
    void findBalance() {
        setBalance(5000);

        assertThat(this.creditWallet.findBalance(userId)).isEqualTo(5000L);
    }

    @Test
    @DisplayName("잔액이 충분하면 차감하고 차감 후 잔액을 반환")
    void tryDeduct_enough(){
        setBalance(5000);

        OptionalLong remaining = this.creditWallet.tryDeduct(userId, 3900);

        assertThat(remaining).hasValue(1100L);
        assertThat(balanceInDb()).isEqualTo(1100L);
    }

    @Test
    @DisplayName("잔액과 가격이 같으면 0까지 차감")
    void tryDeduct_exact() {
        setBalance(3900);

        OptionalLong remaining = creditWallet.tryDeduct(userId, 3900);

        assertThat(remaining).hasValue(0L);
        assertThat(balanceInDb()).isZero();
    }

    @Test
    @DisplayName("잔액이 부족하면 차감하지 않고 empty를 반환")
    void tryDeduct_notEnough() {
        setBalance(3000);

        OptionalLong remaining = creditWallet.tryDeduct(userId, 3900);

        assertThat(remaining).isEmpty();
        assertThat(balanceInDb()).isEqualTo(3000L);
    }
}
