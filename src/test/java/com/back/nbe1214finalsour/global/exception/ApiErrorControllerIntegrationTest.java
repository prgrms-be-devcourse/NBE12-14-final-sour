package com.back.nbe1214finalsour.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import jakarta.servlet.Filter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 필터에서 던진 예외가 서블릿 컨테이너의 {@code /error} 디스패치를 거쳐 ApiResponse로 응답되는지 확인한다.
 * MockMvc는 오류 디스패치를 하지 않으므로 실제 서버를 띄운다.
 */
@IntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ApiErrorControllerIntegrationTest {

    private static final String FAILING_PATH = "/api/test/filter-error";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("필터 예외는 500 COMMON_INTERNAL_ERROR JSON으로 응답하고 예외 정보는 노출하지 않는다")
    void filterException() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + FAILING_PATH))
                .header("Accept", "text/html")
                // /api/** 는 로그인이 필요하다. 인증을 통과해야 보안 필터 뒤의 실패 필터까지 간다
                .header("Authorization", "Bearer " + jwtProvider.createAccessToken(new AuthUser(1L, 1L, 34L, Role.USER), null))
                .GET()
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/json"));
        assertThat(response.body())
                .contains("\"code\":\"COMMON_INTERNAL_ERROR\"")
                .contains("\"message\":\"서버 오류가 발생했습니다.\"")
                .contains("\"data\":null")
                .doesNotContain("secret-detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("trace");
    }

    @TestConfiguration
    static class FailingFilterConfig {

        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            Filter filter = (request, response, chain) -> {
                throw new IllegalStateException("secret-detail");
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(FAILING_PATH);
            return registration;
        }
    }
}
