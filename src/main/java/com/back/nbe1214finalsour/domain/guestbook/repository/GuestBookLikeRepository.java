package com.back.nbe1214finalsour.domain.guestbook.repository;

import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBookLike;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBookLikeId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestBookLikeRepository extends JpaRepository<GuestBookLike, GuestBookLikeId> {
}
