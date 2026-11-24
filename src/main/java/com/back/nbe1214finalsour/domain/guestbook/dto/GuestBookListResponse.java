package com.back.nbe1214finalsour.domain.guestbook.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record GuestBookListResponse(List<Item> items, String nextCursor, boolean hasNext) {

    public record Item(Long id, String content, int likeCount, boolean likedByMe,
                       Author author, OffsetDateTime createdAt) {
    }

    public record Author(String nickname, String universityName) {
    }
}
