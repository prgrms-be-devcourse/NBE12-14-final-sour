package com.back.nbe1214finalsour.domain.guestbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class GuestBookLikeIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UniversityRepository universityRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmailHasher emailHasher;
    @Autowired JwtProvider jwtProvider;

    private Long guestbookId;
    private Long targetUniversityId;
    private String token;
    private Long userId;

    @BeforeEach
    void setUp() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        University target = universityRepository.saveAndFlush(new University("좋아요대상대학교" + suffix));
        targetUniversityId = target.getId();
        University authorUniversity = universityRepository.saveAndFlush(new University("좋아요작성자대학교" + suffix));
        Department department = departmentRepository.saveAndFlush(new Department(authorUniversity, "좋아요테스트학과"));
        String email = "guestbook-like-" + suffix + "@example.ac.kr";
        User user = userRepository.saveAndFlush(User.create(authorUniversity.getId(), department.getId(),
                "좋아요작성" + suffix, email, emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
        userId = user.getId();
        guestbookId = jdbcTemplate.queryForObject("""
                INSERT INTO guest_book (user_id, university_id, content)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, userId, target.getId(), "좋아요 테스트 글");
        token = jwtProvider.createAccessToken(new AuthUser(userId, authorUniversity.getId(),
                department.getId(), Role.USER), null);
    }

    @Test
    void like_once_updatesRowAndCount_duplicateDoesNotIncrement() throws Exception {
        mockMvc.perform(post("/api/v1/guestbooks/{id}/likes", guestbookId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.guestbookId").value(guestbookId))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        mockMvc.perform(post("/api/v1/guestbooks/{id}/likes", guestbookId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GUEST_ALREADY_LIKED"));

        Integer likes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM guest_book_like WHERE guest_book_id = ? AND user_id = ?",
                Integer.class, guestbookId, userId);
        Integer count = jdbcTemplate.queryForObject("SELECT like_count FROM guest_book WHERE id = ?",
                Integer.class, guestbookId);
        assertThat(likes).isEqualTo(1);
        assertThat(count).isEqualTo(1);

        // 기존 목록 조회 API의 likedByMe와 likeCount에도 변경 사항이 반영된다.
        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true));
    }

    @Test
    void nonexistentGuestbook_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/guestbooks/{id}/likes", guestbookId + 1_000_000L)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    }

    @Test
    void nonPositiveId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/guestbooks/{id}/likes", -1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    void anonymousRequest_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/guestbooks/{id}/likes", guestbookId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }
}
