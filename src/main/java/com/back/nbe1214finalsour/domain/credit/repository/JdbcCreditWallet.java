package com.back.nbe1214finalsour.domain.credit.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.OptionalLong;

@Repository
@RequiredArgsConstructor
public class JdbcCreditWallet implements CreditWallet {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long findBalance(Long userId) {
        return this.jdbcTemplate.queryForObject(
                "SELECT credit_balance FROM users WHERE id = ?", Long.class, userId);
    }

    @Override
    public OptionalLong tryDeduct(Long userId, long amount){
        List<Long> balances = this.jdbcTemplate.queryForList("""
                UPDATE users SET credit_balance = credit_balance - ?
                WHERE id = ? AND credit_balance >= ?
                RETURNING credit_balance
                """, Long.class, amount, userId, amount);

        return balances.isEmpty() ? OptionalLong.empty() : OptionalLong.of(balances.getFirst());
    }
}
