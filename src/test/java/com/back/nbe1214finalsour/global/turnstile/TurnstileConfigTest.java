package com.back.nbe1214finalsour.global.turnstile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;

/**
 * Turnstile 설정 검증 (SCRUM-117, 계획 P10). 꺼져 있으면 아무 값 없이도 시작하고,
 * 켰는데 값이 빠졌거나 prod 허용 목록에 로컬 주소가 있으면 시작하지 않는다.
 */
class TurnstileConfigTest {

    /** 개발자 셸의 TURNSTILE_* 환경 변수가 결과를 바꾸지 않도록 시스템 환경 변수를 뺀다 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withUserConfiguration(TurnstileConfig.class);

    @Test
    @DisplayName("기본값은 꺼짐이고 secret 없이도 시작한다. 시간 초과 기본값은 3초다")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TurnstileVerifier.class);
            TurnstileProperties properties = context.getBean(TurnstileProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    @DisplayName("prod에서도 꺼져 있으면 secret 없이 시작한다 (프론트가 붙기 전에 머지해도 안전)")
    void prodDisabledStarts() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("켰는데 secret이 없으면 TURNSTILE_SECRET을 알리며 시작하지 않는다")
    void enabledWithoutSecretFails() {
        runner.withPropertyValues("turnstile.enabled=true", "turnstile.hostnames=localhost")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("TURNSTILE_SECRET"));
    }

    @Test
    @DisplayName("켰는데 허용 hostname이 없으면 TURNSTILE_HOSTNAMES를 알리며 시작하지 않는다")
    void enabledWithoutHostnamesFails() {
        runner.withPropertyValues("turnstile.enabled=true", "turnstile.secret=s")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("TURNSTILE_HOSTNAMES"));
    }

    @Test
    @DisplayName("prod 허용 목록에 localhost가 있으면 시작하지 않는다")
    void prodRejectsLocalhost() {
        runner.withPropertyValues("spring.profiles.active=prod", "turnstile.enabled=true", "turnstile.secret=s",
                        "turnstile.hostnames=www.devcproject.cloud,localhost")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("localhost"));
    }

    @Test
    @DisplayName("prod 허용 목록에 127.0.0.1이 있으면 시작하지 않는다")
    void prodRejectsLoopback() {
        runner.withPropertyValues("spring.profiles.active=prod", "turnstile.enabled=true", "turnstile.secret=s",
                        "turnstile.hostnames=127.0.0.1")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("127.0.0.1"));
    }

    @Test
    @DisplayName("prod에서 운영 도메인만 있으면 Cloudflare 구현으로 시작한다. 시간 초과를 바꿀 수 있다")
    void prodWithProductionHostStarts() {
        runner.withPropertyValues("spring.profiles.active=prod", "turnstile.enabled=true", "turnstile.secret=s",
                        "turnstile.hostnames=www.devcproject.cloud", "turnstile.timeout=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBean(TurnstileVerifier.class)
                            .isInstanceOf(CloudflareTurnstileVerifier.class)
                            .satisfies(verifier -> assertThat(verifier.enabled()).isTrue());
                    assertThat(context.getBean(TurnstileProperties.class).timeout()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("꺼진 구현은 Cloudflare를 부르지 않고 enabled=false, 결과는 통과다")
    void disabledVerifierPasses() {
        runner.run(context -> {
            TurnstileVerifier verifier = context.getBean(TurnstileVerifier.class);
            assertThat(verifier).isInstanceOf(DisabledTurnstileVerifier.class);
            assertThat(verifier.enabled()).isFalse();
            assertThat(verifier.verify(null, "abuse_click", null)).isEqualTo(TurnstileResult.PASSED);
        });
    }

    @Test
    @DisplayName("TurnstileVerifier 빈 정의는 하나다 (조건별 @Bean으로 나누면 IDE·@MockitoBean이 후보를 둘로 본다)")
    void singleBeanDefinition() {
        assertThat(Arrays.stream(TurnstileConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> TurnstileVerifier.class.isAssignableFrom(method.getReturnType())))
                .withFailMessage("TurnstileVerifier를 만드는 @Bean 메서드는 하나여야 합니다. 구현 선택은 그 메서드 안에서 하세요.")
                .hasSize(1);
    }

    @Test
    @DisplayName("로컬에서는 localhost를 허용한다. 쉼표 사이 공백과 빈 칸은 무시한다")
    void localAllowsLocalhostAndTrims() {
        runner.withPropertyValues("turnstile.enabled=true", "turnstile.secret=s",
                        "turnstile.hostnames= localhost , 127.0.0.1 ,")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TurnstileProperties.class).hostnames())
                            .containsExactly("localhost", "127.0.0.1");
                });
    }

    @Test
    @DisplayName("설정 객체를 문자열로 바꿔도 secret이 드러나지 않는다 (로그·오류 메시지 보호)")
    void toStringMasksSecret() {
        TurnstileProperties properties = new TurnstileProperties(true, "very-secret-value", null, null);

        assertThat(properties.toString()).doesNotContain("very-secret-value").contains("****");
    }
}
