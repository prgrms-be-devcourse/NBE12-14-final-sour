package com.back.nbe1214finalsour.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
}
