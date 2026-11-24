package com.back.nbe1214finalsour.global.turnstile;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cloudflare siteverify로 토큰을 확인한다 (SCRUM-117, 계획 P5·P6·P13).
 *
 * <ul>
 *   <li>Cloudflare가 답한 실패는 {@link TurnstileResult#REJECTED}다. 가짜 토큰까지 통과하면 안 되기 때문이다.</li>
 *   <li>Cloudflare에 닿지 못하면 같은 {@code idempotency_key}로 한 번 더 시도하고, 그래도 안 되면
 *       {@link TurnstileResult#UNAVAILABLE}이다. 호출하는 쪽이 이 경우를 통과시킨다 (fail-open).</li>
 *   <li>토큰과 secret은 로그에 남기지 않는다. Cloudflare의 {@code error-codes}와 상태 코드만 남긴다.</li>
 * </ul>
 *
 * @see <a href="https://developers.cloudflare.com/turnstile/get-started/server-side-validation/">서버 검증 (2026-11-16)</a>
 */
@Slf4j
public class CloudflareTurnstileVerifier implements TurnstileVerifier {

    static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    /** 공식 문서: 토큰은 최대 2048자 */
    static final int MAX_TOKEN_LENGTH = 2048;
    private static final int MAX_ATTEMPTS = 2;
    private static final String INTERNAL_ERROR = "internal-error";
    /** 우리 설정이 틀렸다는 뜻이라 ERROR로 남긴다. 사용자 잘못이 아니지만 fail-open으로 숨기지 않는다 */
    private static final Set<String> SECRET_ERRORS = Set.of("missing-input-secret", "invalid-input-secret");

    private final RestClient restClient;
    private final String secret;
    private final Set<String> allowedHostnames;

    public CloudflareTurnstileVerifier(RestClient restClient, String secret, List<String> allowedHostnames) {
        this.restClient = restClient;
        this.secret = secret;
        this.allowedHostnames = Set.copyOf(allowedHostnames);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public TurnstileResult verify(@Nullable String token, String expectedAction, @Nullable String clientIp) {
        String response = token == null ? "" : token;
        if (response.length() > MAX_TOKEN_LENGTH) {
            log.info("Turnstile 거절: 토큰이 {}자를 넘음 (action={})", MAX_TOKEN_LENGTH, expectedAction);
            return TurnstileResult.REJECTED;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", response);
        if (clientIp != null && !clientIp.isBlank()) {
            form.add("remoteip", clientIp);
        }
        // 재시도가 같은 요청임을 Cloudflare가 알 수 있게 한다 (재시도 때 토큰이 '이미 사용'으로 거절되지 않도록)
        form.add("idempotency_key", UUID.randomUUID().toString());

        String lastProblem = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Attempt result = callOnce(form);
            if (result.answer() != null) {
                return judge(result.answer(), expectedAction);
            }
            lastProblem = result.problem();
        }
        log.warn("Turnstile 연결 불가, 통과시킴(fail-open): {} (action={}, {}번 시도)", lastProblem, expectedAction, MAX_ATTEMPTS);
        return TurnstileResult.UNAVAILABLE;
    }

    /** Cloudflare의 판정을 얻으면 {@code answer}, 닿지 못했거나 다시 물어야 하면 {@code problem}을 채운다 */
    private Attempt callOnce(MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is5xxServerError() || status.value() == 429) {
                            return Attempt.unavailable("HTTP " + status.value());
                        }
                        if (status.is4xxClientError()) {
                            // Cloudflare가 요청 자체를 거절했다. 토큰 판정과 같게 본다
                            return Attempt.answered(new SiteverifyResponse(false, null, null, List.of("HTTP " + status.value())));
                        }
                        SiteverifyResponse body = response.bodyTo(SiteverifyResponse.class);
                        if (body == null || body.success() == null) {
                            return Attempt.unavailable("알 수 없는 응답 (HTTP " + status.value() + ")");
                        }
                        if (!body.success() && body.errorCodes().contains(INTERNAL_ERROR)) {
                            return Attempt.unavailable(INTERNAL_ERROR);
                        }
                        return Attempt.answered(body);
                    });
        } catch (RestClientException e) {
            // 연결 실패·시간 초과(ResourceAccessException), JSON이 아닌 응답 등
            return Attempt.unavailable(e.getClass().getSimpleName());
        }
    }

    private TurnstileResult judge(SiteverifyResponse answer, String expectedAction) {
        if (!Boolean.TRUE.equals(answer.success())) {
            if (answer.errorCodes().stream().anyMatch(SECRET_ERRORS::contains)) {
                log.error("Turnstile secret 설정 오류: {}. TURNSTILE_SECRET을 확인하세요", answer.errorCodes());
            } else {
                log.info("Turnstile 거절: {} (action={})", answer.errorCodes(), expectedAction);
            }
            return TurnstileResult.REJECTED;
        }
        if (!expectedAction.equals(answer.action())) {
            log.info("Turnstile 거절: action 불일치 (기대={}, 응답={})", expectedAction, answer.action());
            return TurnstileResult.REJECTED;
        }
        if (answer.hostname() == null || !allowedHostnames.contains(answer.hostname())) {
            log.info("Turnstile 거절: 허용하지 않은 hostname {} (action={})", answer.hostname(), expectedAction);
            return TurnstileResult.REJECTED;
        }
        return TurnstileResult.PASSED;
    }

    private record Attempt(@Nullable SiteverifyResponse answer, String problem) {

        static Attempt answered(SiteverifyResponse answer) {
            return new Attempt(answer, "");
        }

        static Attempt unavailable(String problem) {
            return new Attempt(null, problem);
        }
    }

    /** siteverify 응답 중 판정에 쓰는 필드만 읽는다. 나머지(challenge_ts, cdata, metadata)는 무시한다 */
    record SiteverifyResponse(
            @Nullable Boolean success,
            @Nullable String hostname,
            @Nullable String action,
            @JsonProperty("error-codes") @Nullable List<String> errorCodes) {

        SiteverifyResponse {
            errorCodes = errorCodes == null ? List.of() : errorCodes;
        }
    }
}
