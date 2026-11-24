package com.back.nbe1214finalsour.global.security.jwt;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import org.jspecify.annotations.Nullable;

/**
 * access 토큰을 검증해 로그인 회원을 돌려준다. HTTP 요청({@link JwtAuthenticationFilter})과 STOMP CONNECT가 같은 규칙을 쓴다.
 *
 * <p>세션 (계정당 1기기, SCRUM-86): 토큰에 {@code sid}가 있으면 {@link LoginSessionStore}의 현재 세션과 비교한다.
 * <ul>
 *   <li>다르면 다른 기기에서 로그인해 끊긴 세션이다: {@code AUTH_SESSION_REPLACED}</li>
 *   <li>Redis에 없으면(유실·로그아웃) {@code COMMON_EXPIRED_TOKEN}: 프론트가 재발급하면 행의 세션으로 복구된다</li>
 *   <li>{@code sid}가 없으면(SCRUM-84 배포 전 발급) 비교하지 않는다. 최대 access 토큰 유효 시간 뒤 사라진다</li>
 * </ul>
 * Redis 장애는 삼키지 않고 그대로 던진다.
 *
 * <p>빈이 아니다. {@code SecurityConfig}를 import하는 여러 슬라이스 테스트가 새 빈을 몰라도 되도록, 쓰는 쪽이 직접 만든다.
 */
public class AccessTokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final LoginSessionStore loginSessionStore;

    public AccessTokenAuthenticator(JwtProvider jwtProvider, LoginSessionStore loginSessionStore) {
        this.jwtProvider = jwtProvider;
        this.loginSessionStore = loginSessionStore;
    }

    /**
     * {@code Authorization} 헤더 값에서 토큰을 꺼낸다. {@code Bearer }(대소문자 무시)로 시작하지 않으면 null이다.
     */
    public static @Nullable String bearerToken(@Nullable String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * @throws BusinessException 만료·세션 없음이면 {@code COMMON_EXPIRED_TOKEN}, 위조·형식 오류면 {@code COMMON_INVALID_TOKEN},
     *                           다른 기기 로그인이면 {@code AUTH_SESSION_REPLACED}
     */
    public AuthUser authenticate(String token) {
        AccessTokenPayload payload = this.jwtProvider.parseAccessTokenPayload(token);
        AuthUser authUser = payload.user();
        if (payload.sessionId() != null) {
            verifyCurrentSession(authUser.userId(), payload.sessionId());
        }
        return authUser;
    }

    private void verifyCurrentSession(Long userId, String sessionId) {
        String current = this.loginSessionStore.find(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.COMMON_EXPIRED_TOKEN, "현재 세션 없음(유실·로그아웃) userId=" + userId));
        if (!current.equals(sessionId)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_REPLACED, "다른 기기에서 로그인한 세션 userId=" + userId);
        }
    }
}
