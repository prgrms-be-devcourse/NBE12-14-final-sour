package com.back.nbe1214finalsour.domain.guestbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.back.nbe1214finalsour.domain.forbiddenword.service.ForbiddenWordFilter;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBook;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookRepository;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GuestBookWriteServiceTest {
    @Mock GuestBookRepository guestBookRepository;
    @Mock UniversityRepository universityRepository;
    @Mock UserRepository userRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    GuestBookWriteService service;
    User author;

    @BeforeEach
    void setUp() {
        service = new GuestBookWriteService(guestBookRepository, universityRepository, userRepository,
                new ForbiddenWordFilter(), redisTemplate);
        author = User.create(7L, 8L, "작성자", "unit@example.ac.kr", "hash", "password", OffsetDateTime.now());
        ReflectionTestUtils.setField(author, "id", 5L);
    }

    private void givenValidAuthor() {
        given(userRepository.findById(5L)).willReturn(Optional.of(author));
        given(universityRepository.existsById(12L)).willReturn(true);
    }

    @Test
    void savesMaskedContentAndReturnsListItem() {
        givenValidAuthor();
        University university = new University("작성자대학교");
        given(universityRepository.findById(7L)).willReturn(Optional.of(university));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(RedisKey.RATELIMIT_GUESTBOOK.of(5L), "1", Duration.ofMinutes(1)))
                .willReturn(true);
        OffsetDateTime now = OffsetDateTime.parse("2026-11-19T10:00:00+09:00");
        given(guestBookRepository.saveAndFlush(any(GuestBook.class))).willAnswer(invocation -> {
            GuestBook saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 31L);
            ReflectionTestUtils.setField(saved, "createdAt", now);
            return saved;
        });

        var result = service.create(12L, 5L, "  너 바보야 BadWord  ");

        assertThat(result.id()).isEqualTo(31L);
        assertThat(result.content()).isEqualTo("너 ***야 ***");
        assertThat(result.likeCount()).isZero();
        assertThat(result.likedByMe()).isFalse();
        assertThat(result.author().nickname()).isEqualTo("작성자");
        assertThat(result.author().universityName()).isEqualTo("작성자대학교");
        assertThat(result.createdAt()).isEqualTo(now);
        var captor = ArgumentCaptor.forClass(GuestBook.class);
        verify(guestBookRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUniversityId()).isEqualTo(12L);
        assertThat(captor.getValue().getUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getContent()).isEqualTo("너 ***야 ***");
    }

    @Test
    void ownUniversityIsForbiddenBeforeRedisReservation() {
        given(userRepository.findById(5L)).willReturn(Optional.of(author));
        given(universityRepository.existsById(7L)).willReturn(true);
        assertError(ErrorCode.GUEST_OWN_UNIVERSITY, () -> service.create(7L, 5L, "응원"));
        verify(redisTemplate, never()).opsForValue();
        verify(guestBookRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownUniversityReturns404() {
        given(userRepository.findById(5L)).willReturn(Optional.of(author));
        assertError(ErrorCode.UNIV_NOT_FOUND, () -> service.create(999L, 5L, "응원"));
        verify(guestBookRepository, never()).saveAndFlush(any());
    }

    @Test
    void blankOrTooLongContentReturns400() {
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.create(12L, 5L, "   "));
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.create(12L, 5L, "가".repeat(501)));
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.create(12L, 5L, null));
        verify(userRepository, never()).findById(any());
    }

    @Test
    void suspendedAuthorReturns403() {
        author.changeStatus(UserStatus.SUSPENDED);
        given(userRepository.findById(5L)).willReturn(Optional.of(author));
        assertError(ErrorCode.USER_SUSPENDED, () -> service.create(12L, 5L, "응원"));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void unknownAuthorReturns404() {
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.create(12L, 5L, "응원"));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void maskedContentBeyondDbLimitReturns400BeforeRateLimit() {
        givenValidAuthor();
        given(universityRepository.findById(7L)).willReturn(Optional.of(new University("작성자대학교")));
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.create(12L, 5L, "바보".repeat(250)));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void rateLimitReturns429() {
        givenValidAuthor();
        given(universityRepository.findById(7L)).willReturn(Optional.of(new University("작성자대학교")));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(RedisKey.RATELIMIT_GUESTBOOK.of(5L), "1", Duration.ofMinutes(1)))
                .willReturn(false);
        assertError(ErrorCode.GUEST_TOO_FREQUENT, () -> service.create(12L, 5L, "응원"));
        verify(guestBookRepository, never()).saveAndFlush(any());
    }

    private static void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
