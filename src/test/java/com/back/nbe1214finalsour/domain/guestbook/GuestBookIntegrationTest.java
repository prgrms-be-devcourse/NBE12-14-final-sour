package com.back.nbe1214finalsour.domain.guestbook;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class GuestBookIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UniversityRepository universityRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmailHasher emailHasher;
    @Autowired JwtProvider jwtProvider;

    private Long targetUniversityId;
    private Long otherUniversityId;
    private User author;
    private Long newestId;
    private Long middleId;
    private Long oldestId;

    @BeforeEach
    void setUp() {
        University target = universityRepository.saveAndFlush(new University("방명록조회대상대학교"));
        University authorUniversity = universityRepository.saveAndFlush(new University("방명록작성자대학교"));
        targetUniversityId = target.getId();
        otherUniversityId = authorUniversity.getId();
        Department department = departmentRepository.saveAndFlush(new Department(authorUniversity, "테스트학과"));
        String email = "guestbook-integration@example.ac.kr";
        author = userRepository.saveAndFlush(User.create(authorUniversity.getId(), department.getId(),
                "방명록작성자", email, emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));

        oldestId = insertGuestBook(targetUniversityId, "가장 오래된 글");
        middleId = insertGuestBook(targetUniversityId, "중간 글");
        newestId = insertGuestBook(targetUniversityId, "가장 최근 글");
        insertGuestBook(otherUniversityId, "다른 학교 글");
        jdbcTemplate.update("INSERT INTO guest_book_like (guest_book_id, user_id) VALUES (?, ?)",
                newestId, author.getId());
        jdbcTemplate.update("UPDATE guest_book SET like_count = 1 WHERE id = ?", newestId);
    }

    private Long insertGuestBook(Long universityId, String content) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO guest_book (user_id, university_id, content)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, author.getId(), universityId, content);
    }

    @Test
    void anonymousRequest_returnsTargetOnly_sortedById() throws Exception {
        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(newestId))
                .andExpect(jsonPath("$.data.items[1].id").value(middleId))
                .andExpect(jsonPath("$.data.items[2].id").value(oldestId))
                .andExpect(jsonPath("$.data.items[0].author.nickname").value("방명록작성자"))
                .andExpect(jsonPath("$.data.items[0].author.universityName").value("방명록작성자대학교"))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[0].createdAt").isString())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value((Object) null));
    }

    @Test
    void cursor_pagination_doesNotRepeatAndFindsRemaining() throws Exception {
        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(newestId))
                .andExpect(jsonPath("$.data.items[1].id").value(middleId))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(middleId.toString()));

        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .param("size", "2").param("cursor", middleId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(oldestId))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void loggedInUser_receivesLikedByMeTrueOnlyOnTheirLikedPost() throws Exception {
        var token = jwtProvider.createAccessToken(new AuthUser(author.getId(), otherUniversityId,
                author.getDepartmentId(), Role.USER), null);
        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[1].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[2].likedByMe").value(false));
    }

    @Test
    void nonexistentUniversity_returns404() throws Exception {
        Long nonexistentId = targetUniversityId + 10000000L;
        assertThat(universityRepository.existsById(nonexistentId)).isFalse();
        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", nonexistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));
    }
}
