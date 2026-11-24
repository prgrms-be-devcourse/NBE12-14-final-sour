package com.back.nbe1214finalsour.domain.guestbook.dto;

import org.jspecify.annotations.Nullable;

/** 방명록 작성 요청. 글자 수와 공백 검사는 서비스에서 유니코드 코드 포인트 기준으로 수행한다. */
public record GuestBookCreateRequest(@Nullable String content) {
}
