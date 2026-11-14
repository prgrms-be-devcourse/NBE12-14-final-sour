package com.back.nbe1214finalsour.global.security;

import com.back.nbe1214finalsour.global.security.jwt.JwtAuthenticationFilter;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security 설정. 요청마다 {@link JwtAuthenticationFilter}가 access 토큰으로 로그인 사용자를 채운다.
 *
 * <p>401/403은 컨트롤러 밖(필터)에서 결정되므로 {@code GlobalExceptionHandler}가 아니라
 * {@link ApiAuthenticationEntryPoint}와 {@link ApiAccessDeniedHandler}가 {@code ApiResponse}로 응답한다.
 * CORS 규칙은 {@link CorsConfig}에 있다.
 *
 * <p>URL 규칙은 API 명세서 §2의 인증 표기를 따른다: 불필요·선택·쿠키는 {@link #PUBLIC_POST}·{@link #PUBLIC_GET},
 * 관리자는 {@code /api/v1/admin/**}, 그 밖의 {@code /api/**}는 로그인 필요. 표가 바뀌면 이 목록과
 * {@code UrlAuthorizationRulesTest}의 표를 함께 고친다.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 로그인 없이 호출하는 POST (API 명세서 §2 인증 "불필요"·"쿠키"). 재발급은 access 토큰 대신 refresh 쿠키로 확인한다.
     * 경로는 URL 접두어 결정(회원 MVP 계획 Q1)에 따라 명세서의 {@code /api/...} 대신 {@code /api/v1/...}이다.
     */
    static final String[] PUBLIC_POST = {
            "/api/v1/auth/email/code",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/reissue",
    };

    /**
     * 로그인 없이 호출하는 GET (API 명세서 §2 인증 "불필요"·"선택"). "선택" 경로는 토큰이 유효하면
     * {@code @AuthenticationPrincipal AuthUser}가 채워지고, 없거나 만료·위조면 {@code null}이다 (계획 Q4).
     * 공개 범위가 넓어지지 않도록 {@code /**} 대신 명세서 경로를 그대로 적는다 ({@code *}는 경로 한 칸).
     */
    static final String[] PUBLIC_GET = {
            "/api/v1/users/nickname/availability",
            "/api/v1/clickers",
            "/api/v1/items",
            "/api/v1/universities",
            "/api/v1/universities/*",                           // 선택
            "/api/v1/universities/*/departments",
            "/api/v1/map/universities",
            "/api/v1/rankings/universities",                    // 선택
            "/api/v1/rankings/universities/*/departments",      // 선택
            "/api/v1/rankings/universities/*/users",            // 선택
    };

    private final ApiAuthenticationEntryPoint authenticationEntryPoint;
    private final ApiAccessDeniedHandler accessDeniedHandler;
    private final JwtProvider jwtProvider;
    private final LoginSessionStore loginSessionStore;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, loginSessionStore), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole(Role.ADMIN.name())
                        // 명세서 인증 "필요"와 표에 없는 API. 새 API는 여기에 걸리므로 공개하려면 위 목록에 넣는다
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
