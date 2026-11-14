package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 로그아웃: refresh 토큰 행을 지우고, 그 행의 세션이 현재 세션이면 Redis 세션 키도 지운다 (SCRUM-87).
 * 키가 없어지면 이 기기에 남은 access 토큰도 바로 {@code COMMON_EXPIRED_TOKEN}이 되고, 재발급할 행도 없어 로그인 화면으로 간다.
 *
 * <p>이미 다른 기기에서 로그인해 끊긴 세션이면 키(새 기기의 세션)는 두고 행만 지운다.
 * Redis는 행 삭제가 커밋된 뒤 바꾼다. 트랜잭션을 걸지 않아 {@code revoke}가 끝나면 커밋된다.
 */
@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenService refreshTokenService;
    private final LoginSessionStore loginSessionStore;

    public void logout(Long userId, String refreshToken) {
        refreshTokenService.revoke(refreshToken, userId)
                .ifPresent(sessionId -> loginSessionStore.deleteIfMatches(userId, sessionId.toString()));
    }
}
