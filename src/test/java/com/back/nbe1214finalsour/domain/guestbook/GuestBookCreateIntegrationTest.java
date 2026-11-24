package com.back.nbe1214finalsour.domain.guestbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Transactional
class GuestBookCreateIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UniversityRepository universityRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmailHasher emailHasher;
    @Autowired JwtProvider jwtProvider;
    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper jsonMapper;

    Long targetUniversityId;
    Long ownUniversityId;
    User author;
    String token;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        University target = universityRepository.saveAndFlush(new University("방명록작성대상" + suffix));
        University own = universityRepository.saveAndFlush(new University("방명록작성소속" + suffix));
        targetUniversityId = target.getId();
        ownUniversityId = own.getId();
        Department department = departmentRepository.saveAndFlush(new Department(own, "테스트학과"));
        String email = "guest-write-" + suffix + "@example.ac.kr";
        author = userRepository.saveAndFlush(User.create(own.getId(), department.getId(),
                "방명록" + suffix, email, emailHasher.hash(email), "{noop}unused", OffsetDateTime.now()));
        token = jwtProvider.createAccessToken(
                new AuthUser(author.getId(), ownUniversityId, department.getId(), Role.USER), null);
    }

    @AfterEach
    void clearRedis() {
        if (author != null) {
            redis.delete(RedisKey.RATELIMIT_GUESTBOOK.of(author.getId()));
        }
    }

    private ResultActions write(Long universityId, String content) throws Exception {
        return mockMvc.perform(post("/api/v1/universities/{id}/guestbooks", universityId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("content", content))));
    }

    @Test
    void successfulWrite201AndStoredMaskedContent() throws Exception {
        write(targetUniversityId, "  바보 BadWord 응원  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").value("*** *** 응원"))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.likedByMe").value(false))
                .andExpect(jsonPath("$.data.author.nickname").value(author.getNickname()))
                .andExpect(jsonPath("$.data.author.universityName").isString())
                .andExpect(jsonPath("$.data.createdAt").isString());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT content FROM guest_book WHERE user_id = ? AND university_id = ?
                """, String.class, author.getId(), targetUniversityId)).isEqualTo("*** *** 응원");

        mockMvc.perform(get("/api/v1/universities/{id}/guestbooks", targetUniversityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value("*** *** 응원"))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(false));
    }

    @Test
    void anonymousCannotWrite() throws Exception {
        mockMvc.perform(post("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"응원\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownUniversityReturns400() throws Exception {
        write(ownUniversityId, "응원")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUEST_OWN_UNIVERSITY"));
    }

    @Test
    void missingUniversityReturns404() throws Exception {
        write(targetUniversityId + 1000000L, "응원")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIV_NOT_FOUND"));
    }

    @Test
    void invalidContentReturns400() throws Exception {
        write(targetUniversityId, "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        write(targetUniversityId, "가".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
        mockMvc.perform(post("/api/v1/universities/{id}/guestbooks", targetUniversityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    void secondWriteWithinOneMinuteReturns429() throws Exception {
        write(targetUniversityId, "첫 번째")
                .andExpect(status().isCreated());
        write(targetUniversityId, "두 번째")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("GUEST_TOO_FREQUENT"));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM guest_book WHERE user_id = ? AND university_id = ?",
                Integer.class, author.getId(), targetUniversityId);
        assertThat(count).isEqualTo(1);
    }
}
