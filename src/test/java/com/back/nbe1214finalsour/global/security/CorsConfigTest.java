package com.back.nbe1214finalsour.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CORS (SCRUM-62). 재발급·로그아웃은 refresh 쿠키를 보내야 하므로 credentials를 허용하고 origin은 목록으로만 연다.
 * origin 목록은 환경 변수 {@code CORS_ALLOWED_ORIGINS}처럼 쉼표로 구분한 문자열로 넣는다.
 */
@WebMvcTest(controllers = CorsConfigTest.TestController.class)
@Import({
        CorsConfigTest.TestController.class,
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = "cors.allowed-origins=https://a.example,https://b.example")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @ParameterizedTest
    @ValueSource(strings = {"https://a.example", "https://b.example"})
    @DisplayName("허용한 origin의 preflight는 그 origin과 credentials 허용을 돌려준다")
    void allowedPreflight(String origin) throws Exception {
        mockMvc.perform(options("/api/test/cors")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("허용하지 않은 origin의 preflight는 403이고 CORS 헤더가 없다")
    void disallowedPreflight() throws Exception {
        mockMvc.perform(options("/api/test/cors")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @WithMockUser
    @DisplayName("허용한 origin의 실제 요청에도 CORS 헤더가 붙는다")
    void allowedActualRequest() throws Exception {
        mockMvc.perform(get("/api/test/cors").header(HttpHeaders.ORIGIN, "https://a.example"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://a.example"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("401 응답에도 CORS 헤더가 붙어, 다른 origin의 프론트가 오류 코드(COMMON_EXPIRED_TOKEN 등)를 읽을 수 있다")
    void unauthorizedResponseHasCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/test/cors").header(HttpHeaders.ORIGIN, "https://a.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://a.example"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @RestController
    static class TestController {

        @GetMapping("/api/test/cors")
        ApiResponse<Void> cors() {
            return ApiResponse.ok();
        }
    }
}
