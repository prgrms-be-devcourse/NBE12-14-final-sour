package com.back.nbe1214finalsour.global.security;

import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 컨트롤러 밖(Security 필터, 요청 한도 필터 등)에서 {@link ApiResponse} 형식으로 오류를 응답할 때 쓴다.
 * 컨트롤러 안에서는 {@code BusinessException}을 던지면 된다.
 */
@Component
@RequiredArgsConstructor
public class ApiResponseWriter {

    private final JsonMapper jsonMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(), ApiResponse.fail(errorCode));
    }
}
