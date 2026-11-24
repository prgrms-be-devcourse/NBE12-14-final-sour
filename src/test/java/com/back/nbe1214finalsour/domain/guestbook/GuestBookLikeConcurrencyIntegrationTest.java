package com.back.nbe1214finalsour.domain.guestbook;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트 메서드에 @Transactional을 붙이지 않아 서로 다른 DB 트랜잭션에서 경합한다. */
@IntegrationTest
class GuestBookLikeConcurrencyIntegrationTest {
    @Autowired GuestBookLikeService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired UniversityRepository universityRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired EmailHasher emailHasher;

    private Long targetUniversityId;
    private Long ownUniversityId;
    private Long departmentId;
    private Long guestbookId;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        University target = universityRepository.saveAndFlush(new University("동시좋아요대상" + suffix));
        University own = universityRepository.saveAndFlush(new University("동시좋아요소속" + suffix));
        targetUniversityId = target.getId();
        ownUniversityId = own.getId();
        departmentId = departmentRepository.saveAndFlush(new Department(own, "테스트학과")).getId();

        firstUserId = createUser(suffix, "a");
        secondUserId = createUser(suffix, "b");
        guestbookId = jdbc.queryForObject("""
                INSERT INTO guest_book (user_id, university_id, content)
                VALUES (?, ?, '동시 좋아요 테스트') RETURNING id
                """, Long.class, firstUserId, targetUniversityId);
    }

    private Long createUser(String suffix, String kind) {
        String email = "like-race-" + kind + suffix + "@example.ac.kr";
        User user = User.create(ownUniversityId, departmentId, "동시" + kind + suffix,
                email, emailHasher.hash(email), "{noop}unused", OffsetDateTime.now());
        return userRepository.saveAndFlush(user).getId();
    }

    @Test
    void sameUserConcurrentLikes_onlyOneSucceeds() throws Exception {
        boolean[] results = concurrentLikes(firstUserId, firstUserId);
        assertThat(results[0] ^ results[1]).isTrue();
        assertThat(rowCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    void differentUsersConcurrentLikes_bothCountsArePreserved() throws Exception {
        boolean[] results = concurrentLikes(firstUserId, secondUserId);
        assertThat(results).containsExactly(true, true);
        assertThat(rowCount()).isEqualTo(2);
        assertThat(likeCount()).isEqualTo(2);
    }

    private boolean[] concurrentLikes(Long userA, Long userB) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> tryLike(userA, ready, start));
            Future<Boolean> second = executor.submit(() -> tryLike(userB, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return new boolean[]{first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)};
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean tryLike(Long userId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 좋아요 테스트 시작 실패");
        }
        try {
            service.like(guestbookId, userId);
            return true;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.GUEST_ALREADY_LIKED) {
                return false;
            }
            throw e;
        }
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM guest_book_like WHERE guest_book_id = ?",
                Integer.class, guestbookId);
    }

    private int likeCount() {
        return jdbc.queryForObject("SELECT like_count FROM guest_book WHERE id = ?",
                Integer.class, guestbookId);
    }

    @AfterEach
    void cleanUp() {
        if (guestbookId != null) {
            jdbc.update("DELETE FROM guest_book_like WHERE guest_book_id = ?", guestbookId);
            jdbc.update("DELETE FROM guest_book WHERE id = ?", guestbookId);
        }
        if (firstUserId != null) jdbc.update("DELETE FROM users WHERE id = ?", firstUserId);
        if (secondUserId != null) jdbc.update("DELETE FROM users WHERE id = ?", secondUserId);
        if (departmentId != null) jdbc.update("DELETE FROM department WHERE id = ?", departmentId);
        if (targetUniversityId != null) jdbc.update("DELETE FROM university WHERE id = ?", targetUniversityId);
        if (ownUniversityId != null) jdbc.update("DELETE FROM university WHERE id = ?", ownUniversityId);
    }
}
