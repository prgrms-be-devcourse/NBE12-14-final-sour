package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.entity.RefreshToken;
import com.back.nbe1214finalsour.domain.auth.repository.RefreshTokenRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh 토큰 행을 발급·소비·폐기한다. 원문은 호출한 쪽이 쿠키로만 보내고, DB에는 원문의 SHA-256 hex만 저장한다
 * (API 명세서 재발급 "쿠키 값의 SHA-256으로 REFRESH_TOKEN을 찾아", DDL {@code ck_refresh_token_token}).
 *
 * <p>계정당 1기기 (SCRUM-83): 로그인하면 새 세션을 시작하면서 그 회원의 행을 모두 지운다. 그 브라우저의 이전 토큰은
 * 다른 회원의 것이어도 지운다 (SCRUM-72 A). 재발급 때는 그 회원의 만료된 행을 지운다 (SCRUM-72 C).
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final Clock clock;

    /**
     * 로그인: 새 세션 ID를 만들고, 이 회원의 다른 기기 행을 모두 지운 뒤 그 세션의 refresh 토큰을 저장한다.
     * Redis의 현재 세션은 호출한 쪽({@code LoginService})이 커밋 뒤 바꾼다.
     *
     * @param previousToken 이 요청에 실려 온 이전 refresh 토큰 원문 (없으면 {@code null}). 다른 회원의 토큰이어도 지운다 (SCRUM-72 A)
     */
    @Transactional
    public IssuedSession startSession(Long userId, String previousToken) {
        if (previousToken != null && !previousToken.isBlank()) {
            refreshTokenRepository.deleteByToken(hash(previousToken));
        }
        refreshTokenRepository.deleteAllByUserId(userId);

        UUID sessionId = UUID.randomUUID();
        String token = jwtProvider.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(userId, hash(token),
                OffsetDateTime.now(clock).plus(jwtProvider.refreshTokenTtl()), sessionId));
        return new IssuedSession(token, sessionId.toString());
    }

    /**
     * 새 refresh 토큰을 저장하고 원문을 돌려준다.
     *
     * @param previousToken 이 요청에 실려 온 이전 refresh 토큰 원문 (없으면 {@code null}). 이 브라우저에서 곧 덮어써지므로,
     *                      다른 회원의 토큰이어도 지운다 (SCRUM-72 A)
     */
    @Transactional
    public String issue(Long userId, String previousToken) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (previousToken != null && !previousToken.isBlank()) {
            refreshTokenRepository.deleteByToken(hash(previousToken));
        }
        refreshTokenRepository.deleteExpiredByUserId(userId, now);

        String token = jwtProvider.generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(userId, hash(token), now.plus(jwtProvider.refreshTokenTtl()), null));
        return token;
    }

    /**
     * 재발급에 쓸 토큰을 소비한다: 행을 지우고 회원 ID를 돌려준다 (Rotation). 만료된 행도 지운 뒤 거부한다 (SCRUM-72 C).
     *
     * <p>행을 지운 채로 예외를 던지므로 {@link BusinessException}에는 롤백하지 않는다. 같은 트랜잭션에 참여한 호출자도
     * 같은 설정이어야 한다 (안쪽에서 롤백 표시가 되면 바깥 커밋이 실패한다).
     *
     * @throws BusinessException 401 {@code AUTH_INVALID_REFRESH_TOKEN}: 없음, 이미 사용됨, 만료
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Long consume(String token) {
        if (token == null || token.isBlank()) {
            throw invalid("refresh 토큰 쿠키 없음");
        }
        String tokenHash = hash(token);
        RefreshToken refreshToken = refreshTokenRepository.findByToken(tokenHash)
                .orElseThrow(() -> invalid("없거나 이미 사용된 refresh 토큰"));
        if (refreshTokenRepository.deleteByToken(tokenHash) == 0) {
            throw invalid("동시 재발급에서 먼저 사용된 refresh 토큰: userId=" + refreshToken.getUserId());
        }
        if (!refreshToken.getExpiredAt().isAfter(OffsetDateTime.now(clock))) {
            throw invalid("만료된 refresh 토큰: userId=" + refreshToken.getUserId());
        }
        return refreshToken.getUserId();
    }

    /** 로그아웃: 이 회원의 토큰이면 지운다. 없거나 다른 회원의 토큰이면 아무것도 하지 않는다 */
    @Transactional
    public void revoke(String token, Long userId) {
        if (token != null && !token.isBlank()) {
            refreshTokenRepository.deleteByTokenAndUserId(hash(token), userId);
        }
    }

    /** 비밀번호 변경·탈퇴: 이 회원의 모든 기기를 로그아웃시킨다 (추가 명세 §3-1). 이미 발급한 access 토큰은 만료까지 남는다 */
    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.deleteAllByUserId(userId);
    }

    /** refresh 토큰 원문의 SHA-256, 소문자 hex 64자 */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.AUTH_INVALID_REFRESH_TOKEN, detail);
    }

    /**
     * @param refreshToken refresh 토큰 원문. 쿠키로만 보낸다
     * @param sessionId    새 로그인 세션 ID (access 토큰 {@code sid} claim)
     */
    public record IssuedSession(String refreshToken, String sessionId) {

        /** 토큰이 로그에 남지 않도록 가린다 */
        @Override
        public String toString() {
            return "IssuedSession[sessionId=" + sessionId + ", refreshToken=****]";
        }
    }
}
