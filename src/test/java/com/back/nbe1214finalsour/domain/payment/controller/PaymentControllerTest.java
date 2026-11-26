package com.back.nbe1214finalsour.domain.payment.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.service.PaymentService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(PaymentController.class)
@Import({
    SecurityConfig.class,
    CorsConfig.class,
    JwtProvider.class,
    ApiAuthenticationEntryPoint.class,
    ApiAccessDeniedHandler.class,
    ApiResponseWriter.class,
    GlobalExceptionHandler.class
})
class PaymentControllerTest {
    // 토큰의 회원: userId 1, universityId 7, departmentId 34
    private static final AuthUser USER = new AuthUser(1L, 7L, 34L, Role.USER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    // SecurityConfig의 JWT 필터가 쓴다. sid 없는 토큰만 쓰므로 호출되지 않는다
    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PaymentService paymentService;

    private String bearerToken() {
        return "Bearer " + jwtProvider.createAccessToken(USER, null);
    }

    @Test
    @DisplayName("허용된 금액으로 주문하면 201과 orderId·orderName·amount를 응답한다")
    void createOrderReturns201WithOrder() throws Exception {
        // given
        Payment payment = Payment.ready(1L, ChargeAmount.KRW_5000);
        given(paymentService.createOrder(any(), any())).willReturn(payment);

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 5000}
                        """));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value(payment.getOrderId()))
                .andExpect(jsonPath("$.data.orderName").value("크레딧 5,000"))
                .andExpect(jsonPath("$.data.amount").value(5000));
        then(paymentService).should().createOrder(1L, ChargeAmount.KRW_5000);
    }

    @Test
    @DisplayName("허용되지 않은 금액으로 주문하면 400 PAY_INVALID_AMOUNT를 응답하고 주문을 만들지 않는다")
    void createOrderRejectsDisallowedAmount() throws Exception {
        // given: 30,000원은 명세 예시에는 있지만 허용 금액(2,500·5,000·10,000)이 아니다

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 30000}
                        """));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAY_INVALID_AMOUNT"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        then(paymentService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("금액 없이 주문하면 400 COMMON_INVALID_INPUT과 amount 필드 오류를 응답하고 주문을 만들지 않는다")
    void createOrderRejectsMissingAmount() throws Exception {
        // given: 본문에 amount가 없다

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].field").value("amount"));
        then(paymentService).shouldHaveNoInteractions();
    }
}
