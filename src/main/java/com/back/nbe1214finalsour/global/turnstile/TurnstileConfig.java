package com.back.nbe1214finalsour.global.turnstile;

import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Turnstile 확인 구현을 고르고 설정을 검사한다 (SCRUM-117, 계획 P10·P12).
 *
 * <ul>
 *   <li>{@code turnstile.enabled=false}(기본값)면 Cloudflare를 부르지 않는 구현을 쓴다. secret이 없어도 시작한다.</li>
 *   <li>켰는데 secret이나 허용 hostname이 없으면 시작하지 않는다.</li>
 *   <li>prod 허용 목록에 {@code localhost}·{@code 127.0.0.1}이 있으면 시작하지 않는다. 개발용 토큰이 운영에서 통과하지 않게 한다.</li>
 * </ul>
 *
 * <p>빈 정의는 하나로 둔다 ({@code VerificationMailConfig}와 같은 이유: 조건별 {@code @Bean}은 IDE와
 * {@code @MockitoBean}이 후보를 둘로 본다). HTTP 클라이언트는 새 의존성 없이 JDK {@link HttpClient}를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TurnstileProperties.class)
public class TurnstileConfig {

    private static final List<String> LOCAL_HOSTNAMES = List.of("localhost", "127.0.0.1");

    @Bean
    public TurnstileVerifier turnstileVerifier(TurnstileProperties properties, Environment environment) {
        if (!properties.enabled()) {
            return new DisabledTurnstileVerifier();
        }
        if (!properties.hasSecret()) {
            throw new IllegalStateException("turnstile.enabled=true이면 TURNSTILE_SECRET 환경 변수가 필요합니다");
        }
        if (properties.hostnames().isEmpty()) {
            throw new IllegalStateException(
                    "turnstile.enabled=true이면 TURNSTILE_HOSTNAMES 환경 변수(쉼표로 구분한 프론트 hostname)가 필요합니다");
        }
        if (environment.matchesProfiles("prod")) {
            List<String> local = properties.hostnames().stream().filter(LOCAL_HOSTNAMES::contains).toList();
            if (!local.isEmpty()) {
                throw new IllegalStateException(
                        "prod 프로필의 TURNSTILE_HOSTNAMES에 로컬 주소 " + local + "를 넣을 수 없습니다. 운영 프론트 hostname만 넣으세요");
            }
        }

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return new CloudflareTurnstileVerifier(restClient, properties.secret(), properties.hostnames());
    }
}
