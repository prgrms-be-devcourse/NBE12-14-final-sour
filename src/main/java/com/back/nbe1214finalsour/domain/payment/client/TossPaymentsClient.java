package com.back.nbe1214finalsour.domain.payment.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

// 토스페이먼츠 승인 API 호출. 응답 처리는 다음 단계에서 붙인다
public class TossPaymentsClient {

    static final String CONFIRM_URL = "https://api.tosspayments.com/v1/payments/confirm";

    private final RestClient restClient;
    private final String authorization;

    public TossPaymentsClient(RestClient restClient, String secretKey) {
        // 환경 변수가 TOSS_SECRET_KEY= 처럼 비어 있으면 자리 표시자는 풀리므로 여기서 막는다
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("TOSS_SECRET_KEY 환경 변수가 비어 있습니다");
        }
        this.restClient = restClient;
        // 토스 인증 헤더: 시크릿 키 뒤에 콜론을 붙여 Base64로 인코딩한다 (비밀번호 자리는 비움)
        String encoded = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.authorization = "Basic " + encoded;
    }

    // 승인 요청을 보낸다. 지금은 4xx·5xx면 RestClient가 예외를 던진다 (응답 처리 단계에서 바꾼다)
    public void confirm(String paymentKey, String orderId, long amount) {
        restClient.post()
                .uri(CONFIRM_URL)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ConfirmRequest(paymentKey, orderId, amount))
                .retrieve()
                .toBodilessEntity();
    }

    // 토스 승인 요청 본문 { paymentKey, orderId, amount }
    private record ConfirmRequest(String paymentKey, String orderId, long amount) {}
}