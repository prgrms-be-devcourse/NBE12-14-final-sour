package com.back.nbe1214finalsour.domain.alliance;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceService;
import com.back.nbe1214finalsour.domain.chat.repository.AllianceChatRoomRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@IntegrationTest
class AllianceRollbackIntegrationTest {

    @Autowired
    private AllianceService allianceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AllianceChatRoomRepository allianceChatRoomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AllianceUniversityRepository allianceUniversityRepository;

    @Test
    @DisplayName("최초 학교 가입 저장에 실패하면 연합 생성도 롤백된다")
    void createAlliance_rollsBackWhenFounderSaveFails() {

        // given 1. 테스트용 대학교 생성
        Long universityId = jdbcTemplate.queryForObject(
                "INSERT INTO university (name) VALUES (?) RETURNING id",
                Long.class,
                "롤백테스트대학교_" + UUID.randomUUID()
        );

        // given 2. 테스트용 학과 생성
        Long departmentId = jdbcTemplate.queryForObject(
                """
                INSERT INTO department (university_id, name)
                VALUES (?, ?)
                RETURNING id
                """,
                Long.class,
                universityId,
                "컴퓨터공학과"
        );

        Long userId = null;

        try {
            // given 3. 테스트용 회원 생성
            String email =
                    "rollback-" + UUID.randomUUID() + "@test.ac.kr";

            String nickname =
                    "롤백_" + UUID.randomUUID().toString().substring(0, 8);

            User user = User.create(
                    universityId,
                    departmentId,
                    nickname,
                    email,
                    emailHasher.hash(email),
                    passwordEncoder.encode("Passw0rd!"),
                    OffsetDateTime.now()
            );

            userId = userRepository.saveAndFlush(user).getId();

            String allianceName =
                    "롤백테스트연합_" + UUID.randomUUID();

            // given 4. 학교 가입 저장 단계에서 강제로 오류 발생
            when(allianceUniversityRepository
                    .save(any(AllianceUniversity.class)))
                    .thenAnswer(invocation -> {

                        // 오류가 나기 전 연합 INSERT가 수행됐는지 확인
                        Long count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM alliance WHERE name = ?",
                                Long.class,
                                allianceName
                        );

                        assertThat(count).isEqualTo(1L);
                        allianceChatRoomRepository.flush();
                        assertThat(jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM alliance_chat_room WHERE alliance_name = ?",
                                Long.class, allianceName)).isEqualTo(1L);

                        throw new IllegalStateException(
                                "테스트용 학교 가입 저장 실패"
                        );
                    });

            Long finalUserId = userId;

            // when: 연합 생성 시도
            assertThatThrownBy(() ->
                    allianceService.createAlliance(
                            finalUserId,
                            allianceName,
                            24
                    )
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("테스트용 학교 가입 저장 실패");

            // then: 트랜잭션 롤백 후 연합 데이터가 없어야 한다.
            Long allianceCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM alliance WHERE name = ?",
                    Long.class,
                    allianceName
            );

            assertThat(allianceCount).isEqualTo(0L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM alliance_chat_room WHERE alliance_name = ?",
                    Long.class, allianceName)).isZero();

        } finally {
            // 테스트 데이터 정리
            if (userId != null) {
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id = ?",
                        userId
                );
            }

            jdbcTemplate.update(
                    "DELETE FROM department WHERE id = ?",
                    departmentId
            );

            jdbcTemplate.update(
                    "DELETE FROM university WHERE id = ?",
                    universityId
            );
        }
    }
}
