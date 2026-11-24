package com.back.nbe1214finalsour.domain.guestbook.service;

import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListRow;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestBookQueryService {

    private final GuestBookRepository guestBookRepository;
    private final UniversityRepository universityRepository;

    @Transactional(readOnly = true)
    public GuestBookListResponse getGuestBooks(Long universityId, Long cursor, int size, Long viewerUserId) {
        if (!universityRepository.existsById(universityId)) {
            throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        }

        // DB에서 size+1개 읽어 마지막 페이지인지 판단한다. OFFSET을 쓰지 않는다.
        var pageable = PageRequest.of(0, size + 1);
        List<GuestBookListRow> rows = cursor == null
                ? guestBookRepository.findFirstPage(universityId, pageable)
                : guestBookRepository.findNextPage(universityId, cursor, pageable);

        boolean hasNext = rows.size() > size;
        List<GuestBookListRow> page = rows.subList(0, Math.min(size, rows.size()));

        // 비회원이거나 글이 없으면 좋아요 조회 쿼리를 아예 실행하지 않는다.
        Set<Long> likedIds = Set.of();
        if (viewerUserId != null && !page.isEmpty()) {
            List<Long> ids = page.stream().map(GuestBookListRow::id).toList();
            likedIds = new HashSet<>(guestBookRepository.findLikedGuestBookIds(viewerUserId, ids));
        }

        Set<Long> finalLikedIds = likedIds;
        List<GuestBookListResponse.Item> items = page.stream()
                .map(row -> new GuestBookListResponse.Item(
                        row.id(), row.content(), row.likeCount(), finalLikedIds.contains(row.id()),
                        new GuestBookListResponse.Author(row.authorNickname(), row.authorUniversityName()),
                        row.createdAt()))
                .toList();

        String nextCursor = hasNext ? String.valueOf(page.getLast().id()) : null;
        return new GuestBookListResponse(items, nextCursor, hasNext);
    }
}
