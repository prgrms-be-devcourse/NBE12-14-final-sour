package com.back.nbe1214finalsour;


import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class PostgresConnectionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("PostgreSQL에 쿼리를 실행할 수 있다")
    void selectOne() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("연결된 DB가 PostgreSQL이다")
    void isPostgres() {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);

        assertThat(version).startsWith("PostgreSQL");
    }
}