package com.back.nbe1214finalsour.domain.university.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UniversityMemberReader {

    private final JdbcTemplate jdbcTemplate;

    public long countActiveMembers(Long universityId) {
        String sql = """
                SELECT COUNT(*)
                FROM users
                WHERE university_id = ?
                  AND status = 'ACTIVE'
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                universityId
        );

        return count != null ? count : 0L;
    }
}
