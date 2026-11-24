package com.back.nbe1214finalsour.domain.forbiddenword;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** 금칙어 목록 조회 API (SCRUM-130, 계획 D19) */
@IntegrationTest
@AutoConfigureMockMvc
class ForbiddenWordIntegrationTest {

    private static final String PATH = "/api/v1/forbidden-words";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("로그인하면 배포된 금칙어 목록을 받는다")
    void listWords() throws Exception {
        String token = this.jwtProvider.createAccessToken(new AuthUser(1L, 1L, null, Role.USER), null);

        this.mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.words", hasItems("바보", "멍청이", "badword")));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 COMMON_UNAUTHORIZED")
    void requiresLogin() throws Exception {
        this.mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));
    }
}
