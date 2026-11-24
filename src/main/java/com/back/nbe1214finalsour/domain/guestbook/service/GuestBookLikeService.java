package com.back.nbe1214finalsour.domain.guestbook.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestBookLikeService {

    private final GuestBookRepository guestBookRepository;
    private final GuestBookLikeRepository guestBookLikeRepository;
    private final UserRepository userRepository;

    /**
     * 같은 방명록 행에 비관적 쓰기 락을 잡고 JPA로 중복 검사, 저장, 카운트 변경.
     * 트랜잭션이 끝날 때 락이 해제되므로 동시 요청도 순차적으로 처리된다.
     */
    @Transactional
    public GuestBookLikeResponse like(Long guestbookId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        GuestBook guestBook = guestBookRepository.findByIdForUpdate(guestbookId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GUEST_NOT_FOUND));
        GuestBookLikeId likeId = new GuestBookLikeId(guestbookId, userId);
        if (guestBookLikeRepository.existsById(likeId)) {
            throw new BusinessException(ErrorCode.GUEST_ALREADY_LIKED);
        }

        guestBookLikeRepository.save(GuestBookLike.create(guestbookId, userId));
        int likeCount = guestBook.increaseLikeCount();
        // 같은 트랜잭션에서 좋아요 INSERT와 count UPDATE를 커밋한다.
        return new GuestBookLikeResponse(guestbookId, true, likeCount);
    }

    /** 등록과 동일한 글 잠금으로 본인의 좋아요 삭제와 카운트 감소를 직렬화한다. */
    @Transactional
    public GuestBookLikeResponse unlike(Long guestbookId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        GuestBook guestBook = guestBookRepository.findByIdForUpdate(guestbookId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GUEST_NOT_FOUND));
        GuestBookLike like = guestBookLikeRepository.findById(new GuestBookLikeId(guestbookId, userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.GUEST_LIKE_NOT_FOUND));

        int likeCount = guestBook.decreaseLikeCount();
        guestBookLikeRepository.delete(like);
        return new GuestBookLikeResponse(guestbookId, false, likeCount);
    }
}
