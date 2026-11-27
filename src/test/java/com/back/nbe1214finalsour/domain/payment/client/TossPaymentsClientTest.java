package com.back.nbe1214finalsour.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentsClientTest {

    private static final String URL = TossPaymentsClient.CONFIRM_URL;
    private static final String SECRET = "test-only-toss-secret-key";

    private MockRestServiceServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder.build(), SECRET);
    }

    @Test
    @DisplayName("토스가 200과 DONE을 주면 결제수단과 승인 시각을 담은 APPROVED를 돌려준다")
    void returnsApprovedWhenStatusDone() {
        // given
        // orderId, totalAmount는 운영 코드가 읽지 않는 필드다. 모르는 필드를 무시하는지도 함께 확인한다
        String body = """
                {
                  "status": "DONE",
                  "method": "카드",
                  "approvedAt": "2026-11-27T16:00:00+09:00",
                  "orderId": "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b",
                  "totalAmount": 5000
                }
                """;
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.APPROVED);
        assertThat(result.method()).isEqualTo("카드");
        assertThat(result.approvedAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T16:00:00+09:00"));
        assertThat(result.code()).isNull();
        assertThat(result.message()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("승인 요청에 시크릿 키 Basic 인증 헤더와 paymentKey·orderId·amount 본문을 보낸다")
    void sendsBasicAuthHeaderAndJsonBody() {
        // given
        List<String> sentAuthorizations = new ArrayList<>();
        String body = """
                {"status": "DONE", "method": "카드", "approvedAt": "2026-11-27T16:00:00+09:00"}
                """;
        server.expect(once(), requestTo(URL))
                // 람다 안에서 요청이 보낸 Authorization 헤더 값을 꺼내 리스트에 담아 둔다. 단언은 then에서 한다
                .andExpect(request -> sentAuthorizations.add(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"paymentKey":"tgen_20261127160000AbCd1","orderId":"b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b","amount":5000}
                        """))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when
        client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        // 운영 코드의 인코딩 계산을 되풀이하지 않고 거꾸로 푼다: "Basic " 뒤를 Base64 디코드하면 "시크릿 키:"(끝에 콜론)여야 한다
        assertThat(sentAuthorizations).hasSize(1);
        String authorization = sentAuthorizations.get(0);
        assertThat(authorization).startsWith("Basic ");
        String decoded = new String(
                Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("test-only-toss-secret-key:");
        server.verify();
    }

    // 케이스 이름과 200 응답 본문을 묶어 돌려준다. Stream.of는 케이스를 하나씩 이어 붙여 JUnit이 한 건씩 꺼내 쓰게 한다
    static Stream<Arguments> notDoneSuccessBodies() {
        return Stream.of(
                Arguments.of("입금 대기", """
                        {"status": "WAITING_FOR_DEPOSIT", "method": "가상계좌"}
                        """),
                Arguments.of("DONE인데 승인 시각 없음", """
                        {"status": "DONE", "method": "카드"}
                        """));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notDoneSuccessBodies")
    @DisplayName("200이어도 결제가 끝난 상태가 아니면 UNKNOWN을 돌려준다")
    void returnsUnknownWhenSuccessButNotDone(String caseName, String body) {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.UNKNOWN);
        assertThat(result.method()).isNull();
        assertThat(result.approvedAt()).isNull();
    }

    @Test
    @DisplayName("토스가 4xx 거절을 주면 토스 코드·메시지를 담은 REJECTED를 돌려준다")
    void returnsRejectedWhenCardRejected() {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"REJECT_CARD_PAYMENT","message":"한도초과 혹은 잔액부족으로 결제에 실패했습니다."}
                                """));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.REJECTED);
        assertThat(result.code()).isEqualTo("REJECT_CARD_PAYMENT");
        assertThat(result.message()).isEqualTo("한도초과 혹은 잔액부족으로 결제에 실패했습니다.");
    }

    // 시크릿 키 설정 오류 코드와 토스 공식 에러 목록의 상태 코드를 묶어 돌려준다
    static Stream<Arguments> keyErrorResponses() {
        return Stream.of(
                Arguments.of("UNAUTHORIZED_KEY", HttpStatus.UNAUTHORIZED),
                Arguments.of("INVALID_API_KEY", HttpStatus.BAD_REQUEST),
                Arguments.of("INCORRECT_BASIC_AUTH_FORMAT", HttpStatus.FORBIDDEN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyErrorResponses")
    @DisplayName("시크릿 키 설정 오류 코드면 KEY_ERROR를 돌려준다")
    void returnsKeyErrorWhenSecretKeyWrong(String code, HttpStatus status) {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"%s","message":"키 오류 메시지"}
                                """.formatted(code)));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.KEY_ERROR);
        assertThat(result.code()).isEqualTo(code);
    }

    // @ValueSource는 나열한 문자열 하나하나를 인자로 넣어 테스트를 개수만큼 반복 실행한다
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST", "PROVIDER_ERROR"})
    @DisplayName("4xx여도 승인 여부를 알 수 없는 코드면 UNKNOWN을 돌려주고 코드를 남긴다")
    void returnsUnknownWhenOutcomeUncertainCode(String code) {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"%s","message":"승인 여부를 알 수 없는 메시지"}
                                """.formatted(code)));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        // REJECTED가 아니어야 이미 승인됐을 수 있는 결제를 실패로 닫지 않는다
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.UNKNOWN);
        assertThat(result.code()).isEqualTo(code);
    }

    @Test
    @DisplayName("토스가 5xx를 주면 UNKNOWN을 돌려준다")
    void returnsUnknownWhenServerError() {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withServerError()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"FAILED_INTERNAL_SYSTEM_PROCESSING","message":"내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."}
                                """));

        // when
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.UNKNOWN);
        assertThat(result.message()).contains("500");
    }

    @Test
    @DisplayName("토스 응답을 받지 못하면 예외를 던지지 않고 UNKNOWN을 돌려준다")
    void returnsUnknownWhenNoResponse() {
        // given
        server.expect(once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        // when
        // 예외가 밖으로 나오면 이 호출에서 테스트가 실패한다
        TossConfirmResult result = client.confirm("tgen_20261127160000AbCd1", "b6f1e2d3-8a7c-4b5e-9f0a-1c2d3e4f5a6b", 5_000L);

        // then
        assertThat(result.type()).isEqualTo(TossConfirmResult.Type.UNKNOWN);
    }
}
