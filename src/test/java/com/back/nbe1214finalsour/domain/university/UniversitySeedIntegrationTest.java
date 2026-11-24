package com.back.nbe1214finalsour.domain.university;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class UniversitySeedIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UniversityRepository universityRepository;
    @Autowired
    private UniversityCache universityCache;

    @Test
    @DisplayName("Flyway가 공개 학교 76개와 학과 6696개, 숨김 학교 1개와 학과 1개를 등록한다")
    void expectedSeedCounts() {
        Long publicUniversityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM university WHERE NOT hidden", Long.class);
        Long publicDepartmentCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM department d JOIN university u ON u.id = d.university_id
                WHERE NOT u.hidden
                """, Long.class);
        Long hiddenUniversityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM university WHERE hidden", Long.class);
        Long hiddenDepartmentCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM department d JOIN university u ON u.id = d.university_id
                WHERE u.hidden
                """, Long.class);

        assertThat(publicUniversityCount).isEqualTo(76L);
        assertThat(publicDepartmentCount).isEqualTo(6696L);
        assertThat(hiddenUniversityCount).isEqualTo(1L);
        assertThat(hiddenDepartmentCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("V14: 숨김 학교 와플대학교에는 운영팀 학과만 있고, 가입용 이메일 도메인은 없다")
    void hiddenOperatorUniversity() {
        Boolean hidden = jdbcTemplate.queryForObject(
                "SELECT hidden FROM university WHERE name = '와플대학교'", Boolean.class);
        String departments = jdbcTemplate.queryForObject("""
                SELECT string_agg(d.name, ',') FROM department d JOIN university u ON u.id = d.university_id
                WHERE u.name = '와플대학교'
                """, String.class);
        Long domainCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM uni_domain ud JOIN university u ON u.id = ud.university_id
                WHERE u.hidden
                """, Long.class);

        assertThat(hidden).isTrue();
        assertThat(departments).isEqualTo("운영팀");
        assertThat(domainCount).as("숨김 학교 도메인이 있으면 일반 회원이 그 학교로 가입할 수 있다").isZero();
    }

    @Test
    @DisplayName("V14: 서버가 뜰 때 학교 캐시가 와플대학교를 불러 둔다 (채팅·공격·지원의 학교 이름 조회)")
    void universityCacheLoadsHiddenUniversity() {
        Long waffleId = universityRepository.findByName("와플대학교").orElseThrow().getId();

        assertThat(universityCache.getName(waffleId)).isEqualTo("와플대학교");
        assertThat(universityCache.isHidden(waffleId)).isTrue();
        assertThat(universityRepository.findById(waffleId)).get().extracting(University::isHidden).isEqualTo(true);
    }

    @Test
    @DisplayName("모든 학교에 최소 1개 학과가 연결되어 있다")
    void allUniversitiesHaveDepartments() {
        Long missingCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM university u
                WHERE NOT EXISTS (
                    SELECT 1 FROM department d WHERE d.university_id = u.id
                )
                """, Long.class);

        assertThat(missingCount).isZero();
    }

    @Test
    @DisplayName("추가 학교와 학과 데이터가 존재한다")
    void importantSchoolsExist() {
        Long matched = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM university
                WHERE name IN ('서울대학교', '국민대학교', 'DGIST')
                """, Long.class);
        assertThat(matched).isEqualTo(3L);
    }
}
