package com.back.nbe1214finalsour.domain.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
