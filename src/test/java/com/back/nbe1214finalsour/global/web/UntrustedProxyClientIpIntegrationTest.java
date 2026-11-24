package com.back.nbe1214finalsour.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.web.ClientIpIntegrationTest.EchoClientIpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 신뢰 프록시가 아닌 곳에서 보낸 {@code X-Forwarded-For}는 무시한다 (SCRUM-99). 헤더 위조로 IP 한도를 피하지 못하게 한다.
 *
 * <p>신뢰 대역을 루프백이 빠진 값으로 바꿔, 테스트 클라이언트(127.0.0.1)를 프록시를 거치지 않은 사용자로 만든다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(EchoClientIpConfig.class)
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=10.0.0.1/32")
class UntrustedProxyClientIpIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("신뢰하지 않는 곳에서 온 X-Forwarded-For는 무시하고 접속한 주소를 쓴다")
    void untrustedForwardedForIgnored() throws Exception {
        assertThat(ClientIpIntegrationTest.remoteAddr(port, "203.0.113.7")).isEqualTo("127.0.0.1");
    }
}
