package com.back.nbe1214finalsour.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 프록시 뒤에서 {@code request.getRemoteAddr()}가 실제 사용자 IP가 되는지 확인한다 (SCRUM-99, 인증 IP 한도의 기준).
 *
 * <p>Tomcat {@code RemoteIpValve}는 서블릿 필터보다 앞에서 동작하므로 MockMvc로는 확인할 수 없어 실제 서버를 띄운다.
 * 테스트 클라이언트는 루프백(127.0.0.1)에서 접속하므로, 기본 설정에서는 Nginx 같은 신뢰 프록시 역할을 한다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ClientIpIntegrationTest {

    static final String ECHO_PATH = "/api/test/client-ip";

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("신뢰 프록시(루프백)가 보낸 X-Forwarded-For의 사용자 IP를 쓴다")
    void trustedProxyForwardedFor() throws Exception {
        assertThat(remoteAddr(port, "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("사용자가 X-Forwarded-For 앞쪽에 넣은 위조 IP는 무시하고, 신뢰 프록시가 붙인 마지막 IP를 쓴다")
    void forgedLeftmostEntryIgnored() throws Exception {
        // 사용자가 "198.51.100.9"를 보내고, Nginx가 $proxy_add_x_forwarded_for로 실제 접속 IP를 뒤에 붙인 모양
        assertThat(remoteAddr(port, "198.51.100.9, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("X-Forwarded-For가 없으면 접속한 주소를 쓴다")
    void noForwardedFor() throws Exception {
        assertThat(remoteAddr(port, null)).isEqualTo("127.0.0.1");
    }

    /** {@link #ECHO_PATH}로 요청하고 서버가 본 {@code getRemoteAddr()}를 돌려받는다 */
    static String remoteAddr(int port, String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ECHO_PATH)).GET();
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    /** 보안 필터보다 먼저 {@code getRemoteAddr()}를 그대로 응답한다 */
    @TestConfiguration
    static class EchoClientIpConfig {

        @Bean
        FilterRegistrationBean<Filter> echoClientIpFilter() {
            Filter filter = (request, response, chain) -> {
                response.setContentType("text/plain");
                response.getWriter().write(request.getRemoteAddr());
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(ECHO_PATH);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
