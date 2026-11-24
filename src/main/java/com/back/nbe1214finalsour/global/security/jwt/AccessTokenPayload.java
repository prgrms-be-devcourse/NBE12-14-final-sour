package com.back.nbe1214finalsour.global.security.jwt;

import com.back.nbe1214finalsour.global.security.AuthUser;

/**
 * 검증한 access 토큰의 내용.
 *
 * @param sessionId 로그인 세션 ID ({@code sid} claim). 세션 claim을 넣기 전(SCRUM-84 배포 전)에 발급된 토큰이면 {@code null}
 */
public record AccessTokenPayload(AuthUser user, String sessionId) {
}
