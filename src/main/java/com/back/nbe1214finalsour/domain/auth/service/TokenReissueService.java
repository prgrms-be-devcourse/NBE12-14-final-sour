package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.dto.ReissueResponse;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.security.AuthUser;
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
 */
@Service
@RequiredArgsConstructor
public class TokenReissueService {

    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    /**
     * 쓴 행은 거부할 때도 지워야 하므로 {@link BusinessException}에는 롤백하지 않는다.
     * 거부는 새 행을 만들기 전에만 일어난다.
     *
     * @throws BusinessException 401 {@code AUTH_INVALID_REFRESH_TOKEN}
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ReissueResult reissue(String refreshToken) {
        Long userId = refreshTokenService.consume(refreshToken);
        User user = userRepository.findById(userId)
                .filter(found -> found.getDeletedAt() == null && found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> RefreshTokenService.invalid("재발급 거부: 활동 중이 아닌 회원 userId=" + userId));

        String newRefreshToken = refreshTokenService.issue(userId, null);
        String accessToken = jwtProvider.createAccessToken(
                new AuthUser(user.getId(), user.getUniversityId(), user.getDepartmentId(), user.getRole()));
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
