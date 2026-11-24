package com.back.nbe1214finalsour.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서 (Swagger UI: {@code /swagger-ui/index.html}).
 *
 * <p>모든 API에 access 토큰 인증을 걸어 둔다. Swagger UI의 Authorize 버튼에 토큰을 넣으면
 * {@code Authorization: Bearer <토큰>} 헤더가 붙는다. 로그인 없이 호출하는 API에도 자물쇠가 보이지만 호출에는 지장이 없다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NBE12-14-final-sour API")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
