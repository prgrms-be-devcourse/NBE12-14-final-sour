package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.dto.ReissueResponse;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService.ConsumedToken;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService.IssuedSession;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 재발급 (API 명세서 {@code POST /api/auth/reissue}). 쓴 refresh 토큰 행을 지우고 새 행을 만든다 (Rotation).
 *
 * <p>회원이 탈퇴했거나 정지·차단됐으면 행만 지우고 401로 거부한다. 명세서의 재발급 오류는 401 하나뿐이고,
 * 정지된 회원이 재발급으로 로그인 상태를 계속 늘리지 못하게 하기 위함이다.
 *
 * <p>세션 (계정당 1기기, SCRUM-85): 행의 세션을 이어 간다.
 * <ul>
 *   <li>행의 세션 ≠ Redis 현재 세션: 다른 기기에서 로그인했다. 행만 지우고 401 {@code AUTH_SESSION_REPLACED}</li>
 *   <li>Redis에 현재 세션 없음(유실): 행의 세션으로 복구한다</li>
 *   <li>행에 세션 없음(배포 전 행): 로그인처럼 새 세션을 시작하고 이 회원의 다른 행을 지운다. 배포 전 refresh 토큰으로 여러 기기가
 *       계속 쓰지 못하게 한다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TokenReissueService {

    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final LoginSessionStore loginSessionStore;

    /**
     * 쓴 행은 거부할 때도 지워야 하므로 {@link BusinessException}에는 롤백하지 않는다.
     * 거부는 새 행을 만들기 전에만 일어난다.
     *
     * @throws BusinessException 401 {@code AUTH_INVALID_REFRESH_TOKEN}, 401 {@code AUTH_SESSION_REPLACED}
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ReissueResult reissue(String refreshToken) {
        ConsumedToken consumed = refreshTokenService.consume(refreshToken);
        Long userId = consumed.userId();
        User user = userRepository.findById(userId)
                .filter(found -> found.getDeletedAt() == null && found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> RefreshTokenService.invalid("재발급 거부: 활동 중이 아닌 회원 userId=" + userId));

        String sessionId;
        String newRefreshToken;
        if (consumed.sessionId() == null) {
            IssuedSession session = refreshTokenService.startSession(userId, null);
            sessionId = session.sessionId();
            newRefreshToken = session.refreshToken();
        } else {
            sessionId = consumed.sessionId().toString();
            loginSessionStore.find(userId)
                    .filter(current -> !current.equals(sessionId))
                    .ifPresent(current -> {
                        throw new BusinessException(ErrorCode.AUTH_SESSION_REPLACED,
                                "재발급 거부: 다른 기기에서 로그인한 세션 userId=" + userId);
                    });
            newRefreshToken = refreshTokenService.continueSession(userId, consumed.sessionId());
        }
        // 현재 세션으로 정하고 TTL을 늘린다 (유실 복구·배포 전 행의 새 세션 포함)
        loginSessionStore.put(userId, sessionId);

        String accessToken = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), user.getUniversityId(), user.getDepartmentId(), user.getRole()), sessionId);
        return new ReissueResult(new ReissueResponse(accessToken, jwtProvider.accessTokenExpiresInSeconds()),
                newRefreshToken, jwtProvider.refreshTokenTtl());
    }

    /**
     * @param refreshToken    새 refresh 토큰 원문. 쿠키로만 보낸다
     * @param refreshTokenTtl 쿠키 {@code Max-Age}
     */
    public record ReissueResult(ReissueResponse response, String refreshToken, Duration refreshTokenTtl) {

        /** 토큰이 로그에 남지 않도록 가린다 */
        @Override
        public String toString() {
            return "ReissueResult[tokens=****]";
        }
    }
}
