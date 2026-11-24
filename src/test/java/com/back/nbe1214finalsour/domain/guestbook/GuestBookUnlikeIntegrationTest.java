package com.back.nbe1214finalsour.domain.guestbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.guestbook.service.GuestBookLikeService;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 테스트 바깥 트랜잭션 없이 각 서비스 호출의 실제 DB 커밋·롤백과 등록/취소 경합을 검증한다. */
@IntegrationTest
class GuestBookUnlikeIntegrationTest {
    @Autowired GuestBookLikeService service;
    @Autowired UniversityRepository universities;
    @Autowired DepartmentRepository departments;
    @Autowired UserRepository users;
    @Autowired EmailHasher hasher;
    @Autowired JwtProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;

    private Long schoolId;
    private Long departmentId;
    private Long bookId;
    private final List<User> members = new ArrayList<>();

    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        University school = universities.saveAndFlush(new University("취소검증" + suffix));
        schoolId = school.getId();
        departmentId = departments.saveAndFlush(new Department(school, "검증학과")).getId();
        for (int i = 0; i < 8; i++) {
            String email = "unlike-" + suffix + "-" + i + "@example.ac.kr";
            members.add(users.saveAndFlush(User.create(schoolId, departmentId, "취소" + suffix + i,
                    email, hasher.hash(email), "{noop}unused", OffsetDateTime.now())));
        }
        bookId = jdbc.queryForObject("""
                INSERT INTO guest_book (user_id, university_id, content)
                VALUES (?, ?, '등록과 취소 검증') RETURNING id
                """, Long.class, members.getFirst().getId(), schoolId);
    }

    @AfterEach
    void cleanOnlyOwnedFixtures() {
        if (bookId != null) {
            jdbc.update("delete from guest_book_like where guest_book_id = ?", bookId);
            jdbc.update("delete from guest_book where id = ?", bookId);
        }
        for (User member : members) jdbc.update("delete from users where id = ?", member.getId());
        if (departmentId != null) jdbc.update("delete from department where id = ?", departmentId);
        if (schoolId != null) jdbc.update("delete from university where id = ?", schoolId);
    }

    @Test
    void httpUnlikeDeletesOnlyOwnLikeAndRefreshesPersonalizedRead() throws Exception {
        String path = "/api/v1/guestbooks/" + bookId + "/likes";
        mvc.perform(post(path).header("Authorization", token(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.liked").value(true));
        mvc.perform(delete(path).header("Authorization", token(1)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GUEST_LIKE_NOT_FOUND"));
        consistent(1);
        mvc.perform(get("/api/v1/universities/" + schoolId + "/guestbooks").header("Authorization", token(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].likedByMe").value(true));
        mvc.perform(delete(path).header("Authorization", token(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.guestbookId").value(bookId))
                .andExpect(jsonPath("$.data.liked").value(false)).andExpect(jsonPath("$.data.likeCount").value(0));
        mvc.perform(delete(path).header("Authorization", token(0)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GUEST_LIKE_NOT_FOUND"));
        mvc.perform(get("/api/v1/universities/" + schoolId + "/guestbooks").header("Authorization", token(0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].likedByMe").value(false))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(0));
        consistent(0);
    }

    @Test
    void sameUserParallelLikesAndUnlikesEachApplyExactlyOnce() throws Exception {
        assertThat(parallel(changes(true, true))).filteredOn(Boolean::booleanValue).hasSize(1);
        consistent(1);
        assertThat(parallel(changes(false, true))).filteredOn(Boolean::booleanValue).hasSize(1);
        consistent(0);
    }

    @Test
    void manyUsersWithInterleavedLikesAndUnlikesKeepCounterExact() throws Exception {
        assertThat(parallel(changes(true, false))).containsOnly(true);
        consistent(8);
        var mixed = new ArrayList<>(changes(false, false));
        mixed.addAll(changes(true, false));
        parallel(mixed);
        int actual = jdbc.queryForObject("select count(*) from guest_book_like where guest_book_id=?", Integer.class, bookId);
        assertThat(actual).isBetween(0, 8);
        consistent(actual);
        parallel(changes(false, false));
        consistent(0);
    }

    @Test
    void rollbackRevertsBothInsertedAndDeletedLikeAndCounter() {
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.like(bookId, members.getFirst().getId());
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        })).isInstanceOf(BusinessException.class);
        consistent(0);
        service.like(bookId, members.getFirst().getId());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.unlike(bookId, members.getFirst().getId());
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        })).isInstanceOf(BusinessException.class);
        consistent(1);
    }

    @Test
    void corruptCounterFailsWithoutDeletingLike() {
        service.like(bookId, members.getFirst().getId());
        jdbc.update("update guest_book set like_count=0 where id=?", bookId);
        assertThatThrownBy(() -> service.unlike(bookId, members.getFirst().getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_INTERNAL_ERROR));
        assertThat(jdbc.queryForObject("select count(*) from guest_book_like where guest_book_id=?", Integer.class, bookId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select like_count from guest_book where id=?", Integer.class, bookId)).isZero();
    }

    @Test
    void missingPostAndSuspendedMemberCannotUnlike() throws Exception {
        mvc.perform(delete("/api/v1/guestbooks/" + (bookId + 1000000) + "/likes").header("Authorization", token(0)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
        service.like(bookId, members.getFirst().getId());
        jdbc.update("update users set status='SUSPENDED' where id=?", members.getFirst().getId());
        mvc.perform(delete("/api/v1/guestbooks/" + bookId + "/likes").header("Authorization", token(0)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
        consistent(1);
    }

    private String token(int member) {
        return "Bearer " + jwt.createAccessToken(new AuthUser(members.get(member).getId(), schoolId, departmentId, Role.USER), null);
    }

    private void consistent(int expected) {
        assertThat(jdbc.queryForObject("select count(*) from guest_book_like where guest_book_id=?", Integer.class, bookId))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("select like_count from guest_book where id=?", Integer.class, bookId))
                .isEqualTo(expected);
    }

    private List<Boolean> parallel(List<Callable<Boolean>> operations) throws Exception {
        var barrier = new CyclicBarrier(operations.size());
        try (var executor = Executors.newFixedThreadPool(operations.size())) {
            var futures = operations.stream().map(op -> executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return op.call();
            })).toList();
            var results = new ArrayList<Boolean>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private List<Callable<Boolean>> changes(boolean liked, boolean sameUser) {
        return members.stream().<Callable<Boolean>>map(member -> () -> {
            Long userId = sameUser ? members.getFirst().getId() : member.getId();
            try {
                if (liked) service.like(bookId, userId);
                else service.unlike(bookId, userId);
                return true;
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(liked ? ErrorCode.GUEST_ALREADY_LIKED : ErrorCode.GUEST_LIKE_NOT_FOUND);
                return false;
            }
        }).toList();
    }
}
