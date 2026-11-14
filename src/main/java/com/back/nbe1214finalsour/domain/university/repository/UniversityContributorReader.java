package com.back.nbe1214finalsour.domain.university.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class UniversityContributorReader {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<Long, String> findNicknames(
            Long universityId,
            List<Long> userIds
    ) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
                SELECT id, nickname
                FROM users
                WHERE university_id = :universityId
                  AND id IN (:userIds)
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("universityId", universityId)
                .addValue("userIds", userIds);

        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> Map.entry(
                        rs.getLong("id"),
                        rs.getString("nickname")
                )
        ).stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
        ));
    }

    // 공개 랭킹에서 제외할 회원 ID (점수는 삭제하지 않는다)
    public List<Long> findExcludedUserIds(Long universityId) {
        String sql = """
                SELECT id FROM users
                WHERE university_id = :universityId
                  AND (status <> 'ACTIVE' OR deleted_at IS NOT NULL)
                """;

        return jdbcTemplate.queryForList(
                sql,
                new MapSqlParameterSource("universityId", universityId),
                Long.class
        );
    }

    // 랭킹에 표시할 활성 회원 정보
    public record RankingProfile(
            Long userId,
            String nickname,
            String profileImageUrl,
            Long departmentId,
            String departmentName
    ) {}

    public Map<Long, RankingProfile> findRankingProfiles(
            Long universityId,
            List<Long> userIds
    ) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
                SELECT
                    u.id AS user_id,
                    u.nickname,
                    u.logo_url AS profile_image_url,
                    d.id AS department_id,
                    d.name AS department_name
                FROM users u
                JOIN department d
                  ON d.id = u.department_id
                 AND d.university_id = u.university_id
                WHERE u.university_id = :universityId
                  AND u.id IN (:userIds)
                  AND u.status = 'ACTIVE'
                  AND u.deleted_at IS NULL
                """;

        var params = new MapSqlParameterSource()
                .addValue("universityId", universityId)
                .addValue("userIds", userIds);

        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new RankingProfile(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("profile_image_url"),
                        rs.getLong("department_id"),
                        rs.getString("department_name")
                )
        ).stream().collect(Collectors.toMap(
                RankingProfile::userId,
                Function.identity()
        ));
    }
}
