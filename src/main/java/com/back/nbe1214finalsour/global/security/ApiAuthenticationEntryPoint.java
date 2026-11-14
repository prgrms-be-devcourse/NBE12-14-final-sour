package com.back.nbe1214finalsour.global.security;

import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증이 필요한데 인증 정보가 없을 때 401로 응답한다.
 *
 * <p>access 토큰이 만료·위조이거나 세션이 끝나 익명이 된 경우에는 {@link JwtAuthenticationFilter}가 남긴 사유
 * ({@code COMMON_EXPIRED_TOKEN}·{@code COMMON_INVALID_TOKEN}·{@code AUTH_SESSION_REPLACED})로, 토큰이 없으면
 * {@code COMMON_UNAUTHORIZED}로 응답한다. 프론트는 {@code COMMON_EXPIRED_TOKEN}을 받으면 재발급을 시도하고,
 * {@code AUTH_SESSION_REPLACED}(다른 기기에서 로그인)를 받으면 재발급하지 않고 로그인 화면으로 보낸다.
 */
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiResponseWriter apiResponseWriter;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ErrorCode errorCode = request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE) instanceof ErrorCode tokenError
                ? tokenError
                : ErrorCode.COMMON_UNAUTHORIZED;
        apiResponseWriter.write(response, errorCode);
    }
}
