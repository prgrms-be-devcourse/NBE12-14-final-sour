package com.back.nbe1214finalsour.global.security;

import com.back.nbe1214finalsour.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 로그인은 했지만 권한이 부족할 때 403 {@code COMMON_FORBIDDEN}으로 응답한다. */
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiResponseWriter apiResponseWriter;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        apiResponseWriter.write(response, ErrorCode.COMMON_FORBIDDEN);
    }
}
