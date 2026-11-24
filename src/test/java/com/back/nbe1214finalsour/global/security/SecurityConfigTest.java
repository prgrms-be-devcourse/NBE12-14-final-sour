package com.back.nbe1214finalsour.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.response.ApiResponse;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Security 필터를 켠 상태에서 401/403이 API 명세서 §1 형식으로 응답되는지 확인한다.
 * 계획서 T1-4, T1-6.
 */
@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({
        SecurityConfigTest.TestController.class,
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @Nested
    @DisplayName("@PreAuthorize 실패 (T1-4)")
    class MethodSecurity {

        @Test
        @DisplayName("비로그인 사용자는 401 COMMON_UNAUTHORIZED")
        void anonymousGets401() throws Exception {
            mockMvc.perform(get("/api/test/admin"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("권한이 부족한 로그인 사용자는 403 COMMON_FORBIDDEN (500 아님)")
        void userWithoutRoleGets403() throws Exception {
            mockMvc.perform(get("/api/test/admin"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("권한이 없습니다."));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("권한이 있으면 통과한다")
        void adminPasses() throws Exception {
            mockMvc.perform(get("/api/test/admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"));
        }
    }

    @Nested
    @DisplayName("URL 규칙 (T1-6)")
    class UrlRules {

        @Test
        @DisplayName("허용하지 않은 경로에 비로그인으로 요청하면 401 COMMON_UNAUTHORIZED")
        void deniedPathAnonymousGets401() throws Exception {
            mockMvc.perform(get("/internal"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }

        @Test
        @WithMockUser
        @DisplayName("허용하지 않은 경로에 로그인 사용자가 요청하면 403 COMMON_FORBIDDEN")
        void deniedPathUserGets403() throws Exception {
            mockMvc.perform(get("/internal"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }

        @Test
        @DisplayName("명세서 공개 목록에 없는 /api/** 는 비로그인이면 401 COMMON_UNAUTHORIZED (전체 표는 UrlAuthorizationRulesTest)")
        void unlistedApiRequiresLogin() throws Exception {
            mockMvc.perform(get("/api/test/open"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
        }

        @Test
        @WithMockUser
        @DisplayName("CSRF 토큰 없이 POST할 수 있다 (STATELESS API)")
        void csrfDisabled() throws Exception {
            mockMvc.perform(post("/api/test/open"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser
        @DisplayName("인증 정보를 세션에 저장하지 않는다")
        void statelessSession() throws Exception {
            mockMvc.perform(get("/api/test/open"))
                    .andExpect(status().isOk())
                    .andExpect(request().sessionAttributeDoesNotExist("SPRING_SECURITY_CONTEXT"));
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/api/test/admin")
        @PreAuthorize("hasRole('ADMIN')")
        ApiResponse<Void> admin() {
            return ApiResponse.ok();
        }

        @GetMapping("/api/test/open")
        ApiResponse<Void> openGet() {
            return ApiResponse.ok();
        }

        @PostMapping("/api/test/open")
        ApiResponse<Void> openPost() {
            return ApiResponse.ok();
        }

        @GetMapping("/internal")
        ApiResponse<Void> internal() {
            return ApiResponse.ok();
        }
    }
}
