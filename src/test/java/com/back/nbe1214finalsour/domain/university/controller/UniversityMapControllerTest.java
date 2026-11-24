package com.back.nbe1214finalsour.domain.university.controller;

import com.back.nbe1214finalsour.domain.university.dto.UniversityMapResponse;
import com.back.nbe1214finalsour.domain.university.service.UniversityMapService;
import com.back.nbe1214finalsour.global.exception.GlobalExceptionHandler;
import com.back.nbe1214finalsour.global.security.*;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UniversityMapController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
class UniversityMapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private UniversityMapService mapService;

    @Test
    @DisplayName("비로그인 사용자는 기본 50개 학교를 조회할 수 있다")
    void getUniversities_defaultLimit() throws Exception {

        given(mapService.getUniversities(50))
                .willReturn(List.of(
                        new UniversityMapResponse(
                                1L,
                                12L,
                                "서울대학교",
                                null,
                                5000L,
                                new BigDecimal("37.459"),
                                new BigDecimal("126.952")
                        )
                ));

        mockMvc.perform(get("/api/v1/map/universities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].universityId").value(12))
                .andExpect(jsonPath("$.data[0].name").value("서울대학교"))
                .andExpect(jsonPath("$.data[0].totalScore").value(5000))
                .andExpect(jsonPath("$.data[0].latitude").value(37.459))
                .andExpect(jsonPath("$.data[0].longitude").value(126.952));

        verify(mapService).getUniversities(50);
    }

    @Test
    @DisplayName("limit를 지정하면 해당 개수로 조회한다")
    void getUniversities_customLimit() throws Exception {

        given(mapService.getUniversities(10))
                .willReturn(List.of());

        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "10")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(mapService).getUniversities(10);
    }

    @Test
    @DisplayName("limit가 0이면 400을 반환한다")
    void getUniversities_invalidLimitZero() throws Exception {

        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "0")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));

        verifyNoInteractions(mapService);
    }

    @Test
    @DisplayName("limit가 300을 초과하면 400을 반환한다")
    void getUniversities_invalidLimitOverMax() throws Exception {

        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "301")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"));

        verifyNoInteractions(mapService);
    }

    @Test
    @DisplayName("limit가 숫자가 아니면 400을 반환한다")
    void getUniversities_invalidLimitType() throws Exception {

        mockMvc.perform(
                        get("/api/v1/map/universities")
                                .param("limit", "abc")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapService);
    }
}