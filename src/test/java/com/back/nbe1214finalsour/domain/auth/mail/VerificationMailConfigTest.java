package com.back.nbe1214finalsour.domain.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** 설정에 따라 인증 메일 구현이 골라지는지, SMTP 구현이 어떤 메일을 만드는지 확인한다 */
class VerificationMailConfigTest {

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);

    /** 개발자 셸의 SPRING_MAIL_HOST 같은 환경 변수가 결과를 바꾸지 않도록 시스템 환경 변수를 뺀다 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withUserConfiguration(VerificationMailConfig.class)
            .withBean(JavaMailSender.class, () -> javaMailSender);

    @Test
    @DisplayName("spring.mail.host가 있으면 SMTP 구현을 쓴다")
    void smtpWhenMailHostConfigured() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com", "spring.profiles.active=prod")
                .run(context -> assertThat(context).getBean(VerificationMailSender.class)
                        .isInstanceOf(SmtpVerificationMailSender.class));
    }

    @Test
    @DisplayName("spring.mail.host가 없으면 prod가 아닐 때 코드를 로그에 남기는 개발용 구현을 쓴다")
    void loggingWhenMailHostMissing() {
        runner.run(context -> assertThat(context).getBean(VerificationMailSender.class)
                .isInstanceOf(LoggingVerificationMailSender.class));
    }

    @Test
    @DisplayName("prod에서 spring.mail.host가 없으면 코드를 로그에 남기지 않고, 필요한 환경 변수를 알리며 시작에 실패한다")
    void prodWithoutMailHostFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SPRING_MAIL_HOST"));
    }

    @Test
    @DisplayName("VerificationMailSender 빈 정의는 하나다 (조건별 @Bean으로 나누면 IDE·@MockitoBean이 후보를 둘로 본다)")
    void singleBeanDefinition() {
        assertThat(Arrays.stream(VerificationMailConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> VerificationMailSender.class.isAssignableFrom(method.getReturnType())))
                .withFailMessage("VerificationMailSender를 만드는 @Bean 메서드는 하나여야 합니다. "
                        + "구현 선택은 그 메서드 안에서 하세요.")
                .hasSize(1);
    }

    @Test
    @DisplayName("SMTP 구현은 받는 사람, 발신 주소, 제목을 채우고 본문에 코드를 넣는다")
    void smtpMessage() {
        new SmtpVerificationMailSender(javaMailSender, "no-reply@example.com").sendCode("student@snu.ac.kr", "482913");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("student@snu.ac.kr");
        assertThat(message.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(message.getSubject()).isEqualTo(SmtpVerificationMailSender.SUBJECT);
        assertThat(message.getText()).contains("482913").contains("5분");
    }

    @Test
    @DisplayName("SMTP 구현은 발신 주소가 비어 있으면 넣지 않는다 (서버 기본값 사용)")
    void smtpMessageWithoutFrom() {
        new SmtpVerificationMailSender(javaMailSender, "").sendCode("student@snu.ac.kr", "482913");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isNull();
    }
}
