package com.back.nbe1214finalsour.global.turnstile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * siteverify 응답을 통과·거절·연결 불가로 나누는지 확인한다 (SCRUM-117, 계획 P5·P6).
 * 연결 불가만 호출하는 쪽에서 통과(fail-open)시키므로, Cloudflare가 답한 실패가 연결 불가로 새지 않아야 한다.
 */
class CloudflareTurnstileVerifierTest {

    private static final String URL = CloudflareTurnstileVerifier.SITEVERIFY_URL;
    private static final String SECRET = "test-only-turnstile-secret";
    private static final String HOST = "www.devcproject.cloud";
    private static final String ACTION = "abuse_click";
    private static final String TOKEN = "0.real-looking-token";
    private static final String IP = "203.0.113.7";

    private MockRestServiceServer server;
    private CloudflareTurnstileVerifier verifier;
    private final List<Map<String, String>> sentForms = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new CloudflareTurnstileVerifier(builder.build(), SECRET, List.of(HOST, "devcproject.cloud"));
    }

    @Test
    @DisplayName("success·action·hostname이 모두 맞으면 통과다. secret·토큰·IP·idempotency_key를 form으로 보낸다")
    void passed() {
        expectCall(once()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(json(true, HOST, ACTION));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.PASSED);

        server.verify();
        assertThat(sentForms).singleElement().satisfies(form -> {
            assertThat(form).containsEntry("secret", SECRET)
                    .containsEntry("response", TOKEN)
                    .containsEntry("remoteip", IP);
            assertThat(form.get("idempotency_key")).matches("[0-9a-f-]{36}");
        });
    }

    @Test
    @DisplayName("Cloudflare가 success:false로 답하면 거절이다 (가짜·만료·재사용 토큰)")
    void rejectedWhenCloudflareSaysNo() {
        expectCall(once()).andRespond(json(false, null, null, "timeout-or-duplicate"));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.REJECTED);
        server.verify();
    }

    @Test
    @DisplayName("다른 화면의 action이면 거절이다")
    void rejectedWhenActionDiffers() {
        expectCall(once()).andRespond(json(true, HOST, "support_guest"));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.REJECTED);
    }

    @Test
    @DisplayName("허용 목록에 없는 hostname이면 거절이다 (다른 사이트에서 받은 토큰)")
    void rejectedWhenHostnameNotAllowed() {
        expectCall(once()).andRespond(json(true, "evil.example.com", ACTION));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.REJECTED);
    }

    @Test
    @DisplayName("secret 오류도 Cloudflare가 답한 실패라 거절이다 (설정 실수를 fail-open으로 숨기지 않는다)")
    void rejectedWhenSecretInvalid() {
        expectCall(once()).andRespond(json(false, null, null, "invalid-input-secret"));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.REJECTED);
    }

    @Test
    @DisplayName("429가 아닌 4xx는 Cloudflare가 답한 실패라 거절이다")
    void rejectedOnClientError() {
        expectCall(once()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"error-codes\":[\"bad-request\"]}"));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.REJECTED);
    }

    @Test
    @DisplayName("5xx가 두 번이면 연결 불가다. 재시도는 같은 idempotency_key를 쓴다")
    void unavailableAfterServerErrors() {
        expectCall(twice()).andRespond(withServerError());

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.UNAVAILABLE);

        server.verify();
        assertThat(sentForms).hasSize(2);
        assertThat(sentForms.get(1).get("idempotency_key")).isEqualTo(sentForms.get(0).get("idempotency_key"));
    }

    @Test
    @DisplayName("첫 호출이 5xx여도 재시도가 성공하면 통과다")
    void passedOnRetry() {
        expectCall(once()).andRespond(withServerError());
        expectCall(once()).andRespond(json(true, HOST, ACTION));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.PASSED);
        server.verify();
    }

    @Test
    @DisplayName("시간 초과·연결 실패가 두 번이면 연결 불가다")
    void unavailableOnTimeout() {
        expectCall(twice()).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("internal-error가 두 번이면 연결 불가다 (문서: Retry the request)")
    void unavailableOnInternalError() {
        expectCall(twice()).andRespond(json(false, null, null, "internal-error"));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("Cloudflare의 429는 토큰 판정이 아니므로 연결 불가다")
    void unavailableOnTooManyRequests() {
        expectCall(twice()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("JSON이 아닌 응답(프록시 오류 페이지 등)은 연결 불가다")
    void unavailableOnNonJsonBody() {
        expectCall(twice()).andRespond(withSuccess("<html>bad gateway</html>", MediaType.TEXT_HTML));

        assertThat(verifier.verify(TOKEN, ACTION, IP)).isEqualTo(TurnstileResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("토큰이 없어도 siteverify를 부른다. Cloudflare가 답하면 거절, 못 닿으면 연결 불가가 된다 (계획 P6)")
    void missingTokenStillCallsSiteverify() {
        expectCall(once()).andRespond(json(false, null, null, "missing-input-response"));

        assertThat(verifier.verify(null, ACTION, null)).isEqualTo(TurnstileResult.REJECTED);

        server.verify();
        assertThat(sentForms).singleElement().satisfies(form -> {
            assertThat(form).containsEntry("response", "");
            assertThat(form).doesNotContainKey("remoteip");
        });
    }

    @Test
    @DisplayName("2048자를 넘는 토큰은 부르지 않고 거절한다")
    void rejectsTooLongTokenWithoutCall() {
        server.expect(never(), requestTo(URL));

        assertThat(verifier.verify("x".repeat(CloudflareTurnstileVerifier.MAX_TOKEN_LENGTH + 1), ACTION, IP))
                .isEqualTo(TurnstileResult.REJECTED);
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectCall(
            org.springframework.test.web.client.ExpectedCount count) {
        return server.expect(count, requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> sentForms.add(parseForm(((MockClientHttpRequest) request).getBodyAsString())));
    }

    private static org.springframework.test.web.client.ResponseCreator json(
            boolean success, String hostname, String action, String... errorCodes) {
        String codes = Arrays.stream(errorCodes).map(code -> "\"" + code + "\"").collect(Collectors.joining(","));
        String body = """
                {"success":%s,"challenge_ts":"2026-11-17T01:00:00.000Z","hostname":%s,"action":%s,\
                "cdata":"","error-codes":[%s],"metadata":{"interactive":false}}"""
                .formatted(success, quote(hostname), quote(action), codes);
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(key, value);
        }
        return form;
    }
}
