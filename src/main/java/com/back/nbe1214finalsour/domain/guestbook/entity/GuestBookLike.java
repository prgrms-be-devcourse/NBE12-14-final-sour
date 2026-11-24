package com.back.nbe1214finalsour.domain.guestbook.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "guest_book_like")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestBookLike extends BaseTimeEntity {
    @EmbeddedId
    private GuestBookLikeId id;

    public static GuestBookLike create(Long guestBookId, Long userId) {
        GuestBookLike like = new GuestBookLike();
        like.id = new GuestBookLikeId(guestBookId, userId);
        return like;
    }
}
