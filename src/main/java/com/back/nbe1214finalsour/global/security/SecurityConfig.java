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

/** Security 설정. 요청마다 {@link JwtAuthenticationFilter}가 access 토큰으로 로그인 사용자를 채운다. **/
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 로그인 없이 호출하는 POST **/
    static final String[] PUBLIC_POST = {
            "/api/v1/auth/email/code",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/reissue",
            "/api/v1/supports"
    };

    /** 로그인 없이 호출하는 GET **/
    static final String[] PUBLIC_GET = {
            "/api/v1/users/nickname/availability",
            "/api/v1/clickers",
            "/api/v1/items",
            "/api/v1/universities",
            "/api/v1/universities/*",
            "/api/v1/universities/*/departments",
            "/api/v1/universities/*/guestbooks",
            "/api/v1/universities/*/activity",
            "/api/v1/map/universities",
            "/api/v1/rankings/universities",
            "/api/v1/rankings/universities/*/departments",
            "/api/v1/rankings/universities/*/users",
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
                        // 채팅 WebSocket 핸드셰이크. 인증은 STOMP CONNECT 프레임에서 한다 (ChatStompInterceptor, SCRUM-128)
                        .requestMatchers(HttpMethod.GET, "/ws").permitAll()
                        .requestMatchers("/error", "/actuator/health").permitAll()
                        // API 문서 (Swagger UI). 운영은 application-prod.yaml에서 springdoc을 꺼서 열리지 않는다
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
