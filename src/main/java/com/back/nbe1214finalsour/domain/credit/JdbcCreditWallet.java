package com.back.nbe1214finalsour.domain.credit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcCreditWallet implements CreditWallet {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long findBalance(Long userId) {
        return this.jdbcTemplate.queryForObject(
                "SELECT credit_balance FROM users WHERE id = ?", Long.class, userId);
    }
}
