package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceListReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class AllianceOriginMigrationIntegrationTest {
    @Autowired JdbcConnectionDetails database;

    @Test
    void upgradesPopulatedV18WithoutChangingApplicationAndMembershipResults() throws Exception {
        String schema = "alliance_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        // Flyway and setSchema must not change connections returned to the application's pool.
        var dataSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration");
        try {
            configuration.target("18").load().migrate();
            try (var connection = dataSource.getConnection()) {
                connection.setSchema(schema);
                var scopedSource = new SingleConnectionDataSource(connection, true);
                var jdbc = new JdbcTemplate(scopedSource);
                var reader = new AllianceListReader(new NamedParameterJdbcTemplate(scopedSource));
                long founder = member(jdbc, "founder");
                long accepted = member(jdbc, "accepted");
                long pending = member(jdbc, "pending");
                long rejected = member(jdbc, "rejected");
                long alliance = jdbc.queryForObject("INSERT INTO alliance(name, duration_hours, expired_at) VALUES ('legacy', 24, now() + interval '1 day') RETURNING id", Long.class);
                long founderId = membership(jdbc, alliance, founder, founder, "ACCEPTED");
                long acceptedId = membership(jdbc, alliance, accepted, founder, "ACCEPTED");
                long pendingId = membership(jdbc, alliance, pending, null, "PENDING");
                long rejectedId = membership(jdbc, alliance, rejected, null, "REJECTED");
                var oldApplications = jdbc.queryForList("""
                        SELECT id FROM alliance_university WHERE alliance_id=?
                          AND approver_user_id IS DISTINCT FROM applicant_user_id ORDER BY id DESC
                        """, Long.class, alliance);
                var oldMembership = jdbc.queryForList("SELECT university_id FROM alliance_university WHERE alliance_id=? AND status='ACCEPTED' ORDER BY id", Long.class, alliance);

                configuration.target("19").load().migrate();

                assertThat(jdbc.queryForList("SELECT origin FROM alliance_university ORDER BY id", String.class))
                        .containsExactly("FOUNDER", "APPLICATION", "APPLICATION", "APPLICATION");
                assertThat(oldApplications).containsExactly(rejectedId, pendingId, acceptedId);
                assertThat(reader.applications(alliance, null, null, null, 20).items())
                        .extracting(row -> row.id()).containsExactlyElementsOf(oldApplications);
                assertThat(reader.schools(alliance, OffsetDateTime.now()))
                        .extracting(school -> school.id()).containsExactlyElementsOf(oldMembership);
                assertThat(jdbc.queryForObject("SELECT status FROM alliance_university WHERE id=?", String.class, founderId)).isEqualTo("ACCEPTED");
                long inserted = membership(jdbc, alliance, rejected, null, "PENDING");
                assertThat(jdbc.queryForObject("SELECT origin FROM alliance_university WHERE id=?", String.class, inserted)).isEqualTo("APPLICATION");
            }
        } finally {
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static long member(JdbcTemplate jdbc, String name) {
        long school = jdbc.queryForObject("INSERT INTO university(name) VALUES (?) RETURNING id", Long.class, "legacy " + name);
        long dept = jdbc.queryForObject("INSERT INTO department(university_id, name) VALUES (?, 'legacy') RETURNING id", Long.class, school);
        return jdbc.queryForObject("""
                INSERT INTO users(university_id, department_id, nickname, email, email_hash, password, approved_at)
                VALUES (?, ?, ?, ?, md5(?) || md5(?), 'unused', now()) RETURNING id
                """, Long.class, school, dept, name, name + "@test.ac.kr", name, name);
    }

    private static long membership(JdbcTemplate jdbc, long alliance, long applicant, Long approver, String status) {
        return jdbc.queryForObject("""
                INSERT INTO alliance_university(alliance_id, university_id, applicant_user_id, approver_user_id, status)
                SELECT ?, university_id, id, ?, ? FROM users WHERE id=? RETURNING id
                """, Long.class, alliance, approver, status, applicant);
    }
}
