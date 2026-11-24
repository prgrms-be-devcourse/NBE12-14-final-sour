package com.back.nbe1214finalsour.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 컨트롤러 밖(필터·서블릿 컨테이너)에서 난 오류가 {@code /error}로 넘어왔을 때의 응답 형식을 확인한다.
 * MockMvc는 실제 오류 디스패치를 하지 않으므로, 컨테이너가 넣는 요청 속성을 직접 넣어 호출한다.
 * 실제 디스패치 경로는 {@link ApiErrorControllerIntegrationTest}가 확인한다.
 */
@WebMvcTest(controllers = ApiErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("500이면 COMMON_INTERNAL_ERROR로 응답하고 예외 메시지는 노출하지 않는다")
    void internalError() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret-detail"))
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "secret-detail"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(content().string(not(containsString("secret-detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    @DisplayName("4xx 상태는 그대로 두고 상태에 맞는 COMMON 코드로 응답한다")
    void clientErrors() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("HTML을 요청해도, POST로 디스패치돼도 JSON ApiResponse로 응답한다")
    void alwaysJson() throws Exception {
        mockMvc.perform(post("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("클라이언트가 /error를 직접 호출하면 없는 경로로 보고 404로 응답한다")
    void directAccess() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    @DisplayName("오류가 아닌 상태가 넘어오면 500으로 응답한다")
    void nonErrorStatus() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 200))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"));
    }
}
