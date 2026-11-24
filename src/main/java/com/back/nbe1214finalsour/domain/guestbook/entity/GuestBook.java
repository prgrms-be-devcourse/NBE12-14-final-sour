package com.back.nbe1214finalsour.domain.guestbook.entity;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guest_book")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestBook extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "university_id", nullable = false)
    private Long universityId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    /** 좋아요 수 변경은 같은 글을 잠근 트랜잭션에서 처리한다. */
    public int increaseLikeCount() {
        return ++likeCount;
    }

    /** 등록과 같은 글 잠금 아래 호출한다. 잘못된 카운터는 삭제까지 함께 롤백한다. */
    public int decreaseLikeCount() {
        if (likeCount <= 0) {
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "방명록 좋아요 수 불일치 id=" + id);
        }
        return --likeCount;
    }

    /** 작성 시 좋아요 수는 0부터 시작한다. */
    public static GuestBook create(Long userId, Long universityId, String content) {
        GuestBook guestBook = new GuestBook();
        guestBook.userId = userId;
        guestBook.universityId = universityId;
        guestBook.content = content;
        guestBook.likeCount = 0;
        return guestBook;
    }
}
