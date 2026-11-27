package com.back.nbe1214finalsour.domain.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

// 토스페이먼츠 승인 API를 부르고 응답을 TossConfirmResult로 분류한다. 분류 기준은 토스 공식 에러 코드 목록(결제 승인)이다
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

    public TossConfirmResult confirm(String paymentKey, String orderId, long amount) {
        try {
            return restClient.post()
                    .uri(CONFIRM_URL)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmRequest(paymentKey, orderId, amount))
                    // exchange: 4xx·5xx에서 예외를 던지지 않고 응답을 직접 받아 classify에 넘긴다
                    .exchange((request, response) -> classify(response));
        } catch (RestClientException e) {
            // 연결 실패·응답 시간 초과(ResourceAccessException), JSON이 아닌 응답 등. 토스가 처리했는지 알 수 없다
            return unknown(null, e.getClass().getSimpleName());
        }
    }

    // 상태 코드로 먼저 나누고, 본문을 읽어 세부 분류한다
    private TossConfirmResult classify(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return classifySuccess(response.bodyTo(PaymentBody.class));
        }
        if (status.is4xxClientError()) {
            return classifyError(response.bodyTo(ErrorBody.class), status);
        }
        // 5xx와 그 밖의 상태 코드는 토스가 처리했는지 알 수 없다
        return unknown(null, "HTTP " + status.value());
    }

    // 200이어도 DONE이 아니면 결제가 끝난 게 아니다 (예: 가상계좌 WAITING_FOR_DEPOSIT). 충전하지 않도록 결과 모름으로 둔다
    private TossConfirmResult classifySuccess(@Nullable PaymentBody body) {
        if (body == null || !"DONE".equals(body.status()) || body.approvedAt() == null) {
            String received = body == null ? "본문 없음" : body.status();
            return unknown(null, "200인데 DONE 아님: " + received);
        }
        OffsetDateTime approvedAt = OffsetDateTime.parse(body.approvedAt());
        return new TossConfirmResult(TossConfirmResult.Type.APPROVED, body.method(), approvedAt, null, null);
    }

    private TossConfirmResult classifyError(@Nullable ErrorBody body, HttpStatusCode status) {
        if (body == null || body.code() == null) {
            return unknown(null, "에러 본문 없음: HTTP " + status.value());
        }
        String code = body.code();
        if (isKeyError(code)) {
            return new TossConfirmResult(TossConfirmResult.Type.KEY_ERROR, null, null, code, body.message());
        }
        if (isOutcomeUnknown(code)) {
            return unknown(code, body.message());
        }
        return new TossConfirmResult(TossConfirmResult.Type.REJECTED, null, null, code, body.message());
    }

    // 결과 모름을 만든다. 같은 모양이 여러 곳에서 나와서 한 곳에 모았다
    private TossConfirmResult unknown(@Nullable String code, @Nullable String reason) {
        return new TossConfirmResult(TossConfirmResult.Type.UNKNOWN, null, null, code, reason);
    }

    // 우리 시크릿 키 설정이 잘못됐을 때의 코드 (토스 공식 에러 목록)
    private boolean isKeyError(String code) {
        if (code.equals("UNAUTHORIZED_KEY")) {
            return true;
        }
        if (code.equals("INVALID_API_KEY")) {
            return true;
        }
        if (code.equals("INCORRECT_BASIC_AUTH_FORMAT")) {
            return true;
        }
        return false;
    }

    // 4xx인데 승인 여부를 확정할 수 없는 코드 (토스 공식 에러 목록)
    private boolean isOutcomeUnknown(String code) {
        if (code.equals("ALREADY_PROCESSED_PAYMENT")) {
            return true; // 앞선 승인이 이미 성공했을 수 있다
        }
        if (code.equals("ALREADY_PROCESSING_REQUEST")) {
            return true; // 처리 중이다
        }
        if (code.equals("PROVIDER_ERROR")) {
            return true; // 일시 오류
        }
        return false;
    }

    // 토스 승인 요청 본문 { paymentKey, orderId, amount }
    private record ConfirmRequest(String paymentKey, String orderId, long amount) {}

    // 토스 Payment 객체 중 쓰는 필드만 읽는다
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentBody(@Nullable String status, @Nullable String method, @Nullable String approvedAt) {}

    // 토스 에러 응답 { code, message }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(@Nullable String code, @Nullable String message) {}
}