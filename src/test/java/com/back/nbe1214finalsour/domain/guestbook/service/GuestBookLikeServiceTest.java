package com.back.nbe1214finalsour.domain.guestbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookLikeResponse;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBook;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBookLike;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBookLikeId;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookLikeRepository;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestBookLikeServiceTest {
    @Mock GuestBookRepository guestBookRepository;
    @Mock GuestBookLikeRepository likeRepository;
    @Mock GuestBook guestBook;
    @Mock UserRepository userRepository;
    @Mock User user;
    @InjectMocks GuestBookLikeService service;

    private void givenActiveUser() {
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
    }

    @Test
    void like_addsOnceAndReturnsNewCount() {
        givenActiveUser();
        given(guestBookRepository.findByIdForUpdate(10L)).willReturn(Optional.of(guestBook));
        given(guestBook.increaseLikeCount()).willReturn(13);

        GuestBookLikeResponse response = service.like(10L, 7L);

        assertThat(response.guestbookId()).isEqualTo(10L);
        assertThat(response.liked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(13);
        verify(guestBook).increaseLikeCount();
        verify(likeRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateLike_throws409AndDoesNotIncrement() {
        givenActiveUser();
        given(guestBookRepository.findByIdForUpdate(10L)).willReturn(Optional.of(guestBook));
        given(likeRepository.existsById(new GuestBookLikeId(10L, 7L))).willReturn(true);

        assertError(ErrorCode.GUEST_ALREADY_LIKED, () -> service.like(10L, 7L));
        verify(guestBook, never()).increaseLikeCount();
        verify(likeRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonexistentGuestbook_throws404WithoutInserting() {
        givenActiveUser();
        assertError(ErrorCode.GUEST_NOT_FOUND, () -> service.like(999L, 7L));
        verifyNoInteractions(likeRepository);
    }

    @Test
    void missingUser_isRejectedBeforeDatabaseWrite() {
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.like(10L, 7L));
        verifyNoInteractions(likeRepository, guestBookRepository);
    }

    @Test
    void suspendedUser_isRejectedBeforeDatabaseWrite() {
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(user.getStatus()).willReturn(UserStatus.SUSPENDED);
        assertError(ErrorCode.USER_SUSPENDED, () -> service.like(10L, 7L));
        verifyNoInteractions(likeRepository, guestBookRepository);
    }

    @Test
    void unlike_deletesOnlyOwnEntityAndReturnsNewCount() {
        givenActiveUser();
        given(guestBookRepository.findByIdForUpdate(10L)).willReturn(Optional.of(guestBook));
        GuestBookLike like = GuestBookLike.create(10L, 7L);
        given(likeRepository.findById(new GuestBookLikeId(10L, 7L))).willReturn(Optional.of(like));
        given(guestBook.decreaseLikeCount()).willReturn(12);

        GuestBookLikeResponse response = service.unlike(10L, 7L);

        assertThat(response).isEqualTo(new GuestBookLikeResponse(10L, false, 12));
        verify(likeRepository).delete(like);
        verify(guestBook).decreaseLikeCount();
    }

    @Test
    void missingLike_throws404WithoutDeletingOrDecrementing() {
        givenActiveUser();
        given(guestBookRepository.findByIdForUpdate(10L)).willReturn(Optional.of(guestBook));

        assertError(ErrorCode.GUEST_LIKE_NOT_FOUND, () -> service.unlike(10L, 7L));
        verify(likeRepository, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(guestBook, never()).decreaseLikeCount();
    }

    @Test
    void unlike_missingGuestbookIsRejectedBeforeLikeLookup() {
        givenActiveUser();
        assertError(ErrorCode.GUEST_NOT_FOUND, () -> service.unlike(999L, 7L));
        verifyNoInteractions(likeRepository);
    }

    @Test
    void unlike_missingUserIsRejectedBeforeLock() {
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.unlike(10L, 7L));
        verifyNoInteractions(likeRepository, guestBookRepository);
    }

    @Test
    void unlike_allNonActiveStatusesAreRejectedBeforeLock() {
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        for (UserStatus status : UserStatus.values()) {
            if (status == UserStatus.ACTIVE) continue;
            given(user.getStatus()).willReturn(status);
            assertError(ErrorCode.USER_SUSPENDED, () -> service.unlike(10L, 7L));
        }
        verifyNoInteractions(likeRepository, guestBookRepository);
    }

    private static void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
