package com.back.nbe1214finalsour.domain.guestbook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListRow;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class GuestBookQueryServiceTest {
    @Mock GuestBookRepository guestBookRepository;
    @Mock UniversityRepository universityRepository;
    @InjectMocks GuestBookQueryService service;

    private GuestBookListRow row(long id) {
        return new GuestBookListRow(id, "테스트 " + id, 1, "작성자", "작성자대학교",
                OffsetDateTime.parse("2026-11-18T10:00:00+09:00"));
    }

    @Test
    void firstPage_hasNext_andLikedByMe() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(guestBookRepository.findFirstPage(eq(12L), any(Pageable.class)))
                .willReturn(List.of(row(10), row(9), row(8)));
        given(guestBookRepository.findLikedGuestBookIds(5L, List.of(10L, 9L)))
                .willReturn(List.of(9L));

        GuestBookListResponse result = service.getGuestBooks(12L, null, 2, 5L);

        assertThat(result.items()).extracting(GuestBookListResponse.Item::id)
                .containsExactly(10L, 9L);
        assertThat(result.items()).extracting(GuestBookListResponse.Item::likedByMe)
                .containsExactly(false, true);
        assertThat(result.items().getFirst().author().universityName()).isEqualTo("작성자대학교");
        assertThat(result.nextCursor()).isEqualTo("9");
        assertThat(result.hasNext()).isTrue();
        var pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(guestBookRepository).findFirstPage(eq(12L), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void nextPage_withoutLogin_noLikeQuery() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(guestBookRepository.findNextPage(eq(12L), eq(9L), any(Pageable.class)))
                .willReturn(List.of(row(8)));

        GuestBookListResponse result = service.getGuestBooks(12L, 9L, 2, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().likedByMe()).isFalse();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(guestBookRepository, never()).findLikedGuestBookIds(any(), any());
    }

    @Test
    void emptyPage_returnsEmptyResponse() {
        given(universityRepository.existsById(12L)).willReturn(true);
        given(guestBookRepository.findFirstPage(eq(12L), any(Pageable.class))).willReturn(List.of());

        GuestBookListResponse result = service.getGuestBooks(12L, null, 20, 5L);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasNext()).isFalse();
        verify(guestBookRepository, never()).findLikedGuestBookIds(any(), any());
    }

    @Test
    void unknownUniversity_returns404() {
        given(universityRepository.existsById(999L)).willReturn(false);
        assertThatThrownBy(() -> service.getGuestBooks(999L, null, 20, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNIV_NOT_FOUND));
        verify(guestBookRepository, never()).findFirstPage(any(), any());
    }
}
