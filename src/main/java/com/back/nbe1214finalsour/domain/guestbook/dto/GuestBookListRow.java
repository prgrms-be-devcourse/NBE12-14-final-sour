package com.back.nbe1214finalsour.domain.guestbook.dto;

import java.time.OffsetDateTime;

/** 목록 조회에 필요한 필드만 가져오는 내부 조회 DTO. */
public record GuestBookListRow(
        Long id, String content, int likeCount,
        String authorNickname, String authorUniversityName,
        OffsetDateTime createdAt) {
}
