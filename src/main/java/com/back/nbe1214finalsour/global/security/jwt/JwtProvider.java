package com.back.nbe1214finalsour.global.security.jwt;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * access 토큰(JWT)을 발급·검증하고 refresh 토큰 원문을 만든다.
 *
 * <p>access 토큰 claim: {@code sub}=userId, {@code universityId}, {@code departmentId}, {@code role}. 요청마다 DB를
 * 조회하지 않으려고 넣는다 (학과 ID는 클릭의 학과 점수용, SCRUM-81).
 * 검증에 실패하면 {@link BusinessException}을 던진다. 만료면 {@link ErrorCode#COMMON_EXPIRED_TOKEN}(프론트가 재발급할 신호),
 * 그 밖의 위조·형식 오류·필수 claim 누락은 {@link ErrorCode#COMMON_INVALID_TOKEN}이다.
 *
 * <p>{@code departmentId}는 필수가 아니다. 이 claim을 넣기 전에 발급된 토큰(배포 후 최대 access 토큰 유효 시간)을
 * 401로 끊지 않으려고 없으면 {@code null}로 받는다. 값이 있는데 숫자가 아니면 다른 claim처럼 거절한다.
 *
 * <p>refresh 토큰은 JWT가 아니라 256비트 난수다. 원문은 쿠키로 보내고, DB에는 SHA-256 해시만 저장한다 (회원·인증 계획 §4-2).
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtProvider {

    private static final String CLAIM_UNIVERSITY_ID = "universityId";
    private static final String CLAIM_DEPARTMENT_ID = "departmentId";
    private static final String CLAIM_ROLE = "role";
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Clock clock;
    private final JwtParser parser;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public JwtProvider(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트에서 시각을 고정하려고 둔다. */
    JwtProvider(JwtProperties properties, Clock clock) {
        // 32바이트 미만이면 WeakKeyException으로 애플리케이션 시작이 실패한다
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = properties.accessTokenTtl();
        this.refreshTokenTtl = properties.refreshTokenTtl();
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    public String createAccessToken(AuthUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim(CLAIM_UNIVERSITY_ID, user.universityId())
                .claim(CLAIM_DEPARTMENT_ID, user.departmentId())
                .claim(CLAIM_ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @throws BusinessException 만료면 {@code COMMON_EXPIRED_TOKEN}, 그 밖의 오류면 {@code COMMON_INVALID_TOKEN}
     */
    public AuthUser parseAccessToken(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.COMMON_EXPIRED_TOKEN, "만료된 access 토큰: exp=" + e.getClaims().getExpiration());
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return toAuthUser(claims);
    }

    public long accessTokenExpiresInSeconds() {
        return accessTokenTtl.toSeconds();
    }

    /** refresh 토큰 원문: 256비트 난수를 패딩 없는 Base64URL로 (43자). */
    public String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }

    private static AuthUser toAuthUser(Claims claims) {
        if (claims.getExpiration() == null) {
            throw invalid("exp claim 없음");
        }
        try {
            Long userId = Long.valueOf(claims.getSubject());
            Long universityId = claims.get(CLAIM_UNIVERSITY_ID, Long.class);
            Long departmentId = claims.get(CLAIM_DEPARTMENT_ID, Long.class);
            String role = claims.get(CLAIM_ROLE, String.class);
            if (universityId == null || role == null) {
                throw invalid("필수 claim 없음: universityId=" + universityId + ", role=" + role);
            }
            return new AuthUser(userId, universityId, departmentId, Role.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            // NumberFormatException(sub가 숫자가 아님), 알 수 없는 role, claim 타입 불일치
            throw invalid("claim 오류: " + e.getMessage());
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.COMMON_INVALID_TOKEN, detail);
    }
}
