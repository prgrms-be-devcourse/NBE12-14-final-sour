package com.back.nbe1214finalsour.domain.clicker.controller;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerService;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerUpgradeResult;
import com.back.nbe1214finalsour.domain.clicker.service.ClickerUpgradeService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.ApiAccessDeniedHandler;
import com.back.nbe1214finalsour.global.security.ApiAuthenticationEntryPoint;
import com.back.nbe1214finalsour.global.security.ApiResponseWriter;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.CorsConfig;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.SecurityConfig;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClickerController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
public class ClickerControllerTest {

    private static final AuthUser USER = new AuthUser(1L, 7L, 34L, Role.USER);

    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private ClickerService clickerService;

    @MockitoBean
    private ClickerUpgradeService clickerUpgradeService;

    @Test
    @DisplayName("[GET] /api/v1/clickers는 레벨표를 200으로 응답")
    void getLevelTable() throws Exception {
        // given
        given(clickerService.getLevelTable()).willReturn(List.of(
                new Clicker(1L, 10, 0L),
                new Clicker(2L, 20, 300L)
        ));

        // when & then
        mockMvc.perform(get("/api/v1/clickers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("요청이 성공했습니다."))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].level").value(1))
                .andExpect(jsonPath("$.data[0].clickPower").value(10))
                .andExpect(jsonPath("$.data[0].requiredScore").value(0))
                .andExpect(jsonPath("$.data[1].level").value(2))
                .andExpect(jsonPath("$.data[1].clickPower").value(20))
                .andExpect(jsonPath("$.data[1].requiredScore").value(300));
    }

    @Test
    @DisplayName("[POST] /api/v1/clickers/upgrade는 로그인 유저의 클리커를 업그레이드하고 결과를 200으로 응답")
    void upgrade() throws Exception {
        // given: 2레벨로 업그레이드, 다음 레벨 비용 1000, 남은 기여도 700
        given(clickerUpgradeService.upgrade(USER.userId(), USER.universityId())).willReturn(
                new ClickerUpgradeResult(new Clicker(2L, 20, 300L), 1000L, 700L));

        // when & then
        mockMvc.perform(post("/api/v1/clickers/upgrade")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(USER, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.level").value(2))
                .andExpect(jsonPath("$.data.clickPower").value(20))
                .andExpect(jsonPath("$.data.nextLevelRequiredScore").value(1000))
                .andExpect(jsonPath("$.data.myContribScore").value(700));

        verify(clickerUpgradeService).upgrade(USER.userId(), USER.universityId());
    }

    @Test
    @DisplayName("[POST] /api/v1/clickers/upgrade는 비로그인이면 401 COMMON_UNAUTHORIZED, 업그레이드는 실행하지 않음")
    void upgrade_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/clickers/upgrade"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"));

        verify(clickerUpgradeService, never()).upgrade(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[POST] /api/v1/clickers/upgrade는 기여도가 부족하면 400 CLICKER_NOT_ENOUGH_SCORE")
    void upgrade_notEnoughScore() throws Exception {
        given(clickerUpgradeService.upgrade(USER.userId(),  USER.universityId()))
                .willThrow(new BusinessException(ErrorCode.CLICKER_NOT_ENOUGH_SCORE));

        mockMvc.perform(post("/api/v1/clickers/upgrade")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(USER, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CLICKER_NOT_ENOUGH_SCORE"));
    }
}
