package com.back.nbe1214finalsour.domain.chat.dto;

import org.jspecify.annotations.Nullable;

/**
 * 보내는 메시지 (계획 §3-2). 방은 받지 않는다. 서버가 로그인 회원의 소속 학교로 정한다.
 *
 * @param content 본문. 길이 검사(D13)는 {@code ChatMessageService}가 한다
 */
public record ChatSendRequest(@Nullable String content) {
}
