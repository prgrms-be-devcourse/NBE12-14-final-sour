package com.back.nbe1214finalsour.domain.guestbook.dto;

public record GuestBookLikeResponse(Long guestbookId, boolean liked, int likeCount) {
}
