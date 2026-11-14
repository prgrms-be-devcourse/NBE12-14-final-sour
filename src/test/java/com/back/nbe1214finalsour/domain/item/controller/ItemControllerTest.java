package com.back.nbe1214finalsour.domain.item.controller;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;
import com.back.nbe1214finalsour.domain.item.service.ItemService;
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

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ItemController.class)
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        JwtProvider.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiResponseWriter.class,
        GlobalExceptionHandler.class
})
public class ItemControllerTest {
    @Autowired
    private MockMvc mockMvc;

    /** SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다 */
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private ItemService itemService;

    @Test
    @DisplayName("[GET] /api/v1/items는 로그인 없이 아이템 목록을 200으로 응답")
    void getItems() throws Exception {
        // given
        given(itemService.getItems()).willReturn(List.of(
                new Item(1L, "2배 클릭", "10분 동안 클릭 점수 2배", ItemType.MULTIPLIER, PayType.CONTRIBUTION, 3000L, 600, 2.0),
                new Item(2L, "학교 로고 변경권", "우리 학교 로고 변경", ItemType.LOGO_UNIVERSITY, PayType.CREDIT, 3900L, null, null)
        ));

        // when & then: 토큰 없이 요청
        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("2배 클릭"))
                .andExpect(jsonPath("$.data[0].content").value("10분 동안 클릭 점수 2배"))
                .andExpect(jsonPath("$.data[0].type").value("MULTIPLIER"))
                .andExpect(jsonPath("$.data[0].payType").value("CONTRIBUTION"))
                .andExpect(jsonPath("$.data[0].price").value(3000))
                .andExpect(jsonPath("$.data[0].durationSeconds").value(600))
                .andExpect(jsonPath("$.data[1].id").value(2))
                .andExpect(jsonPath("$.data[1].type").value("LOGO_UNIVERSITY"))
                .andExpect(jsonPath("$.data[1].payType").value("CREDIT"))
                .andExpect(jsonPath("$.data[1].durationSeconds").value(nullValue()));
    }
}
