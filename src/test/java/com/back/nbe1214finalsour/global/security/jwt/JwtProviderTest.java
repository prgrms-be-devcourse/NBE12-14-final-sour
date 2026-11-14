package com.back.nbe1214finalsour.global.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-0123456789";
    private static final String OTHER_SECRET = "another-secret-key-for-jwt-provider-9876543";
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final AuthUser USER = new AuthUser(3_000_000_000L, 7L, 34L, Role.ADMIN);
    private static final String SESSION_ID = "6f1c2b9e-3d4a-4e5f-8a7b-0c1d2e3f4a5b";

    private static JwtProperties properties(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(60), Duration.ofDays(30));
    }

    private static JwtProvider providerAt(Instant now) {
        return new JwtProvider(properties(SECRET), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("access 토큰")
    class AccessToken {

        @Test
        @DisplayName("발급한 토큰을 검증하면 같은 AuthUser가 나온다")
        void roundTrip() {
            JwtProvider provider = providerAt(NOW);

            AuthUser parsed = provider.parseAccessToken(provider.createAccessToken(USER, null));

            assertThat(parsed).isEqualTo(USER);
        }

        @Test
        @DisplayName("세션 ID를 넣어 발급하면 sid claim으로 돌아온다 (SCRUM-84)")
        void sessionIdRoundTrip() {
            JwtProvider provider = providerAt(NOW);

            AccessTokenPayload payload = provider.parseAccessTokenPayload(provider.createAccessToken(USER, SESSION_ID));

            assertThat(payload).isEqualTo(new AccessTokenPayload(USER, SESSION_ID));
        }

        @Test
        @DisplayName("sid claim이 없는 토큰(SCRUM-84 배포 전 발급)은 sessionId가 null이고 거절하지 않는다")
        void tokenWithoutSessionClaim() {
            JwtProvider provider = providerAt(NOW);
            String legacy = provider.createAccessToken(USER, null);

            assertThat(provider.parseAccessTokenPayload(legacy)).isEqualTo(new AccessTokenPayload(USER, null));
            String payloadJson = new String(Base64.getUrlDecoder().decode(legacy.split("\\.")[1]), StandardCharsets.UTF_8);
            assertThat(payloadJson).doesNotContain("\"sid\"");
        }

        @Test
        @DisplayName("학과 claim이 없는 토큰(SCRUM-81 배포 전 발급)은 departmentId가 null이고 거절하지 않는다")
        void tokenWithoutDepartmentClaim() {
            var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            String legacy = Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "USER")
                    .expiration(Date.from(NOW.plus(Duration.ofMinutes(10)))).signWith(key).compact();

            assertThat(providerAt(NOW).parseAccessToken(legacy)).isEqualTo(new AuthUser(1L, 7L, null, Role.USER));
        }

        @Test
        @DisplayName("유효 시간은 설정값(60분)이다")
        void expiresInSeconds() {
            assertThat(providerAt(NOW).accessTokenExpiresInSeconds()).isEqualTo(3600);
        }

        @Test
        @DisplayName("만료 시각까지는 통과하고, 지나면 COMMON_EXPIRED_TOKEN")
        void expired() {
            String token = providerAt(NOW).createAccessToken(USER, null);
            Instant expiresAt = NOW.plus(Duration.ofMinutes(60));

            assertThat(providerAt(expiresAt).parseAccessToken(token)).isEqualTo(USER);
            assertThatThrownBy(() -> providerAt(expiresAt.plusSeconds(1)).parseAccessToken(token))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.COMMON_EXPIRED_TOKEN);
        }

        @Test
        @DisplayName("다른 키로 서명한 토큰은 COMMON_INVALID_TOKEN")
        void forgedSignature() {
            String forged = new JwtProvider(properties(OTHER_SECRET), Clock.fixed(NOW, ZoneOffset.UTC)).createAccessToken(USER, null);

            assertInvalid(forged);
        }

        @Test
        @DisplayName("payload를 바꾼 토큰은 COMMON_INVALID_TOKEN")
        void tamperedPayload() {
            String[] parts = providerAt(NOW).createAccessToken(USER, null).split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                    .replace("\"ADMIN\"", "\"USER\"");
            String tampered = parts[0] + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                    + "." + parts[2];

            assertInvalid(tampered);
        }

        @Test
        @DisplayName("서명 없는(alg=none) 토큰은 COMMON_INVALID_TOKEN")
        void unsigned() {
            String unsigned = Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "ADMIN").compact();

            assertInvalid(unsigned);
        }

        @Test
        @DisplayName("JWT 형식이 아니면 COMMON_INVALID_TOKEN")
        void malformed() {
            assertInvalid("not-a-jwt");
            assertInvalid("");
            assertInvalid(null);
        }

        @Test
        @DisplayName("같은 키로 서명했어도 필요한 claim이 없거나 틀리면 COMMON_INVALID_TOKEN")
        void missingOrWrongClaims() {
            var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            Date exp = Date.from(NOW.plus(Duration.ofMinutes(10)));

            assertInvalid(Jwts.builder().claim("universityId", 7L).claim("role", "USER")
                    .expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("abc").claim("universityId", 7L).claim("role", "USER")
                    .expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("role", "USER")
                    .expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "MANAGER")
                    .expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "USER")
                    .signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("universityId", 7L).claim("departmentId", "abc")
                    .claim("role", "USER").expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "USER")
                    .claim("sid", 123).expiration(exp).signWith(key).compact());
            assertInvalid(Jwts.builder().subject("1").claim("universityId", 7L).claim("role", "USER")
                    .claim("sid", " ").expiration(exp).signWith(key).compact());
        }

        private void assertInvalid(String token) {
            assertThatThrownBy(() -> providerAt(NOW).parseAccessToken(token))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.COMMON_INVALID_TOKEN);
            assertThatThrownBy(() -> providerAt(NOW).parseAccessTokenPayload(token))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.COMMON_INVALID_TOKEN);
        }
    }

    @Nested
    @DisplayName("refresh 토큰 원문")
    class RefreshToken {

        @Test
        @DisplayName("256비트 난수를 Base64URL(패딩 없음)로 만들고 매번 다르다")
        void randomBase64Url() {
            JwtProvider provider = providerAt(NOW);

            String first = provider.generateRefreshToken();
            String second = provider.generateRefreshToken();

            assertThat(first).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(first)).hasSize(32);
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("유효 시간은 설정값(30일)이다")
        void ttl() {
            assertThat(providerAt(NOW).refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
        }
    }

    @Test
    @DisplayName("secret이 32바이트보다 짧으면 시작할 때 실패한다")
    void weakSecret() {
        assertThatThrownBy(() -> new JwtProvider(properties("short-secret"), Clock.systemUTC()))
                .isInstanceOf(WeakKeyException.class);
    }
}
