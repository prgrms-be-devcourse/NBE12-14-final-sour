package com.back.nbe1214finalsour.domain.support.mail;

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

/** 설정에 따라 문의 답변 메일 구현이 골라지는지, SMTP 구현이 어떤 메일을 만드는지 확인한다 (SCRUM-113) */
class SupportAnswerMailConfigTest {

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);

    /** 개발자 셸의 SPRING_MAIL_HOST 같은 환경 변수가 결과를 바꾸지 않도록 시스템 환경 변수를 뺀다 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withUserConfiguration(SupportAnswerMailConfig.class)
            .withBean(JavaMailSender.class, () -> javaMailSender);

    @Test
    @DisplayName("spring.mail.host가 있으면 SMTP 구현을 쓴다")
    void smtpWhenMailHostConfigured() {
        runner.withPropertyValues("spring.mail.host=smtp.example.com", "spring.profiles.active=prod")
                .run(context -> assertThat(context).getBean(SupportAnswerMailSender.class)
                        .isInstanceOf(SmtpSupportAnswerMailSender.class));
    }

    @Test
    @DisplayName("spring.mail.host가 없으면 prod가 아닐 때 로그로 대신하는 개발용 구현을 쓴다")
    void loggingWhenMailHostMissing() {
        runner.run(context -> assertThat(context).getBean(SupportAnswerMailSender.class)
                .isInstanceOf(LoggingSupportAnswerMailSender.class));
    }

    @Test
    @DisplayName("prod에서 spring.mail.host가 없으면 필요한 환경 변수를 알리며 시작에 실패한다")
    void prodWithoutMailHostFailsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SPRING_MAIL_HOST"));
    }

    @Test
    @DisplayName("SupportAnswerMailSender 빈 정의는 하나다 (조건별 @Bean으로 나누면 IDE·@MockitoBean이 후보를 둘로 본다)")
    void singleBeanDefinition() {
        assertThat(Arrays.stream(SupportAnswerMailConfig.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> SupportAnswerMailSender.class.isAssignableFrom(method.getReturnType())))
                .withFailMessage("SupportAnswerMailSender를 만드는 @Bean 메서드는 하나여야 합니다. "
                        + "구현 선택은 그 메서드 안에서 하세요.")
                .hasSize(1);
    }

    @Test
    @DisplayName("SMTP 구현은 평문 메일로 받는 사람·발신 주소·'[대대대] 문의 답변: {답변 제목}'을 채우고 본문에 문의 제목과 답변을 넣는다")
    void smtpMessage() {
        new SmtpSupportAnswerMailSender(javaMailSender, "no-reply@example.com")
                .sendAnswer("me@example.com", "아이템 효과가 적용되지 않아요", "문의 답변드립니다", "확인 결과 ...");

        SimpleMailMessage message = sentMessage();
        assertThat(message.getTo()).containsExactly("me@example.com");
        assertThat(message.getFrom()).isEqualTo("no-reply@example.com");
        assertThat(message.getSubject()).isEqualTo("[대대대] 문의 답변: 문의 답변드립니다");
        assertThat(message.getText()).contains("문의 제목: 아이템 효과가 적용되지 않아요").contains("확인 결과 ...");
    }

    @Test
    @DisplayName("답변 제목의 줄바꿈은 메일 제목에서 공백으로 바뀐다 (제목은 한 줄)")
    void subjectIsSingleLine() {
        new SmtpSupportAnswerMailSender(javaMailSender, "").sendAnswer("me@example.com", "문의", "첫 줄\r\n둘째 줄", "내용");

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("[대대대] 문의 답변: 첫 줄 둘째 줄");
        assertThat(message.getFrom()).isNull();
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }
}
