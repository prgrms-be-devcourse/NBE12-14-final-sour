package com.back.nbe1214finalsour.global.security;

import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 프론트(다른 origin)에서 API를 호출할 수 있게 한다. {@link SecurityConfig}의 {@code cors()}가 이 빈을 쓴다.
 *
 * <p>재발급·로그아웃은 refresh 쿠키를 보내야 해서({@code credentials: 'include'}) credentials를 허용한다.
 * credentials를 허용하면 origin에 {@code *}를 쓸 수 없으므로 목록으로만 연다.
 */
@Configuration
@EnableConfigurationProperties(CorsConfig.CorsProperties.class)
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1));
        // credentials와 * origin 조합을 시작할 때 거부한다 (요청 때까지 기다리지 않는다)
        configuration.validateAllowCredentials();

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * @param allowedOrigins 허용할 origin 목록. 환경 변수 {@code CORS_ALLOWED_ORIGINS}에 쉼표로 구분해 넣는다
     */
    @Validated
    @ConfigurationProperties("cors")
    public record CorsProperties(@NotEmpty List<String> allowedOrigins) {
    }
}
