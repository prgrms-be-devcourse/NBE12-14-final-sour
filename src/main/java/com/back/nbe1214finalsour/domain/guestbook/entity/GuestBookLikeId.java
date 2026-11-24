package com.back.nbe1214finalsour.domain.guestbook.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestBookLikeId implements Serializable {
    @Column(name = "guest_book_id", nullable = false)
    private Long guestBookId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public GuestBookLikeId(Long guestBookId, Long userId) {
        this.guestBookId = guestBookId;
        this.userId = userId;
    }
}
