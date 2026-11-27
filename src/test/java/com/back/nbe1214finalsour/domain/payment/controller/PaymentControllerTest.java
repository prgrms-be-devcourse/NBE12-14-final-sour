package com.back.nbe1214finalsour.domain.payment.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.back.nbe1214finalsour.domain.payment.entity.ChargeAmount;
import com.back.nbe1214finalsour.domain.payment.entity.Payment;
import com.back.nbe1214finalsour.domain.payment.entity.PaymentStatus;
import com.back.nbe1214finalsour.domain.payment.service.PaymentConfirmResult;
import com.back.nbe1214finalsour.domain.payment.service.PaymentService;
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
import java.time.OffsetDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final String ORDER_ID = "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b";
    private static final String PAYMENT_KEY = "tgen_20261127160000AbCd1";
    private static final String CODE = "PAY_PROCESS_CANCELED";
    private static final String MESSAGE = "사용자에 의해 결제가 취소되었습니다.";

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

    @Test
    @DisplayName("실패를 기록하면 200과 orderId·ABORTED 상태를 응답한다")
    void recordFailReturns200WithAborted() throws Exception {
        // given: 서비스는 void라 스텁이 없다

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments/fail")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b",
                         "code": "PAY_PROCESS_CANCELED",
                         "message": "사용자에 의해 결제가 취소되었습니다."}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value("b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b"))
                .andExpect(jsonPath("$.data.status").value("ABORTED"));
        then(paymentService).should().recordFail(
                1L, "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", "PAY_PROCESS_CANCELED", "사용자에 의해 결제가 취소되었습니다.");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFailRequests")
    @DisplayName("필수 값이 없거나 길이를 넘으면 400 COMMON_INVALID_INPUT과 그 필드 오류를 응답하고 실패를 기록하지 않는다")
    void recordFailRejectsInvalidInput(String caseName, String body, String expectedField) throws Exception {
        // given: 스텁이 없다

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments/fail")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].field").value(expectedField));
        then(paymentService).shouldHaveNoInteractions();
    }

    // 잘못된 요청 본문과 오류가 나야 하는 필드. 길이 초과는 각 컬럼 길이 + 1자
    // Stream.of는 케이스들을 JUnit에 넘길 묶음으로 만들기만 한다 (변환·필터 없음). 팀의 @MethodSource 관례(Stream<Arguments>)를 따른다
    static Stream<Arguments> invalidFailRequests() {
        return Stream.of(
                arguments("orderId 없음", failBody(null, CODE, MESSAGE), "orderId"),
                arguments("orderId 공백", failBody("   ", CODE, MESSAGE), "orderId"),
                arguments("orderId 65자", failBody("a".repeat(65), CODE, MESSAGE), "orderId"),
                arguments("code 없음", failBody(ORDER_ID, null, MESSAGE), "code"),
                arguments("code 공백", failBody(ORDER_ID, "   ", MESSAGE), "code"),
                arguments("code 101자", failBody(ORDER_ID, "C".repeat(101), MESSAGE), "code"),
                arguments("message 256자", failBody(ORDER_ID, CODE, "가".repeat(256)), "message"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedFailRequests")
    @DisplayName("메시지가 없거나 비었거나 길이가 컬럼 한도와 같으면 200을 응답하고 값을 그대로 서비스에 넘긴다")
    void recordFailAcceptsBoundaryInput(String caseName, String orderId, String code, String message)
            throws Exception {
        // given: 스텁이 없다

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments/fail")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(failBody(orderId, code, message)));

        // then
        result.andExpect(status().isOk());
        then(paymentService).should().recordFail(1L, orderId, code, message);
    }

    @Test
    @DisplayName("주문이 없거나 남의 주문이면 404 PAY_ORDER_NOT_FOUND를 응답한다")
    void recordFailReturns404WhenNotFound() throws Exception {
        // given: 서비스가 주문을 찾지 못했다 (주문이 없거나 남의 주문. 서비스는 둘을 같은 예외로 던진다)
        willThrow(new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND))
                .given(paymentService).recordFail(1L, ORDER_ID, CODE, MESSAGE);

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments/fail")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(failBody(ORDER_ID, CODE, MESSAGE)));

        // then
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAY_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    // 받아 줘야 하는 경계 입력. 길이는 각 컬럼 길이와 같다 (order_id 64, fail_code 100, fail_message 255)
    // Stream.of는 케이스들을 JUnit에 넘길 묶음으로 만들기만 한다 (변환·필터 없음). 팀의 @MethodSource 관례(Stream<Arguments>)를 따른다
    static Stream<Arguments> acceptedFailRequests() {
        return Stream.of(
                arguments("message 없음", ORDER_ID, CODE, null),
                arguments("message 빈 문자열", ORDER_ID, CODE, ""),
                arguments("orderId 64자", "a".repeat(64), CODE, MESSAGE),
                arguments("code 100자", ORDER_ID, "C".repeat(100), MESSAGE),
                arguments("message 255자", ORDER_ID, CODE, "가".repeat(255)));
    }

    // 실패 기록 요청 본문. 값이 null이면 JSON null로 넣는다 (필드를 빼는 것과 검증 결과가 같다)
    private static String failBody(String orderId, String code, String message) {
        return "{\"orderId\":" + jsonString(orderId)
                + ",\"code\":" + jsonString(code)
                + ",\"message\":" + jsonString(message) + "}";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value + "\"";
    }

    @Test
    @DisplayName("승인이 완료되면 200과 주문 상태·결제수단·KST 승인 시각·충전 크레딧·잔액을 응답한다")
    void confirmReturns200WithChargeResult() throws Exception {
        // given: 승인 시각을 일부러 UTC로 준다. 응답은 KST(+09:00)로 바뀌어야 한다
        PaymentConfirmResult done = new PaymentConfirmResult(
                ORDER_ID, PaymentStatus.DONE, "카드", OffsetDateTime.parse("2026-11-27T07:00:00Z"), 5_000L, 12_000L);
        given(paymentService.confirmPayment(1L, ORDER_ID, 5_000L, PAYMENT_KEY)).willReturn(done);

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, bearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey": "tgen_20261127160000AbCd1",
                         "orderId": "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b",
                         "amount": 5000}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.method").value("카드"))
                .andExpect(jsonPath("$.data.approvedAt").value("2026-11-27T16:00:00+09:00"))
                .andExpect(jsonPath("$.data.chargedCredit").value(5000))
                .andExpect(jsonPath("$.data.creditBalance").value(12000));
        then(paymentService).should().confirmPayment(1L, ORDER_ID, 5_000L, PAYMENT_KEY);
    }
}
