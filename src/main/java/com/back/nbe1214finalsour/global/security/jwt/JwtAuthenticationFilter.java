package com.back.nbe1214finalsour.global.security.jwt;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <access 토큰>}을 검증해 {@link AuthUser}를 SecurityContext에 넣는다.
 *
 * <p>토큰이 없거나 틀려도 요청을 막지 않고 익명으로 넘긴다. 인증 "선택" API는 오래된 토큰이 붙어 와도 동작해야 하기 때문이다
 * (회원·인증 계획 Q4). 토큰이 틀린 사유({@code COMMON_EXPIRED_TOKEN}·{@code COMMON_INVALID_TOKEN})는 요청 속성
 * {@link #TOKEN_ERROR_ATTRIBUTE}에 남기고, 보호 경로에서 401을 낼 때 entry point가 이 값으로 코드를 고른다.
 *
 * <p>세션 (계정당 1기기, SCRUM-86): 토큰에 {@code sid}가 있으면 {@link LoginSessionStore}의 현재 세션과 비교한다.
 * <ul>
 *   <li>다르면 다른 기기에서 로그인해 끊긴 세션이다: {@code AUTH_SESSION_REPLACED}</li>
 *   <li>Redis에 없으면(유실·로그아웃) {@code COMMON_EXPIRED_TOKEN}: 프론트가 재발급하면 행의 세션으로 복구된다</li>
 *   <li>{@code sid}가 없으면(SCRUM-84 배포 전 발급) 비교하지 않는다. 최대 access 토큰 유효 시간 뒤 사라진다</li>
 * </ul>
 * Redis 장애는 삼키지 않는다. 익명으로 넘기면 끊긴 기기가 인증 선택 API를 쓰게 되고 원인도 가려진다.
 *
 * <p>빈으로 등록하지 않는다. 빈이면 Spring Boot가 서블릿 필터로도 등록해 Security 필터 체인 밖에서 한 번 더 돈다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 토큰 검증 실패 사유({@link com.back.nbe1214finalsour.global.exception.ErrorCode})를 담는 요청 속성 이름. */
    public static final String TOKEN_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".TOKEN_ERROR";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtProvider jwtProvider;
    private final LoginSessionStore loginSessionStore;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtProvider jwtProvider, LoginSessionStore loginSessionStore) {
        this.jwtProvider = jwtProvider;
        this.loginSessionStore = loginSessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            AccessTokenPayload payload = jwtProvider.parseAccessTokenPayload(token);
            AuthUser authUser = payload.user();
            if (payload.sessionId() != null) {
                verifyCurrentSession(authUser.userId(), payload.sessionId());
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    authUser, null, List.of(new SimpleGrantedAuthority(ROLE_PREFIX + authUser.role().name())));
            SecurityContext context = securityContextHolderStrategy.createEmptyContext();
            context.setAuthentication(authentication);
            securityContextHolderStrategy.setContext(context);
        } catch (BusinessException e) {
            request.setAttribute(TOKEN_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }

    private void verifyCurrentSession(Long userId, String sessionId) {
        String current = loginSessionStore.find(userId).orElseThrow(() -> new BusinessException(
                ErrorCode.COMMON_EXPIRED_TOKEN, "현재 세션 없음(유실·로그아웃) userId=" + userId));
        if (!current.equals(sessionId)) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_REPLACED, "다른 기기에서 로그인한 세션 userId=" + userId);
        }
    }
}
