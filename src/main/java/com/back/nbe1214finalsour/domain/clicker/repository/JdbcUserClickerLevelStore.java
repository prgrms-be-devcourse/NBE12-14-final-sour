package com.back.nbe1214finalsour.domain.clicker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcUserClickerLevelStore implements UserClickerLevelStore {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public long findLevel(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT clicker_id FROM users WHERE id = ?",
                Long.class,
                userId
        );
    }

}
