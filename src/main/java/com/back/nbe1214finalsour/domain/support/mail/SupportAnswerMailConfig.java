package com.back.nbe1214finalsour.domain.support.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 문의 답변 메일 발송 방식을 고른다. 규칙은 {@code VerificationMailConfig}와 같다.
 *
 * <ul>
 *   <li>{@code spring.mail.host}가 있으면 SMTP로 보낸다.</li>
 *   <li>없으면 {@code prod}가 아닌 프로필에서만 로그로 대신하는 개발용 구현을 쓴다.</li>
 *   <li>{@code prod}에서 SMTP 설정이 없으면 앱이 시작하지 않는다.</li>
 * </ul>
 *
 * <p>조건별 {@code @Bean}으로 나누지 않고 빈 정의를 하나로 둔다 (이유는 {@code VerificationMailConfig}와 같다).
 */
@Configuration(proxyBeanMethods = false)
public class SupportAnswerMailConfig {

    static final String MAIL_HOST_PROPERTY = "spring.mail.host";

    @Bean
    public SupportAnswerMailSender supportAnswerMailSender(
            Environment environment,
            ObjectProvider<JavaMailSender> javaMailSender,
            @Value("${verification-mail.from:}") String from) {
        if (environment.containsProperty(MAIL_HOST_PROPERTY)) {
            return new SmtpSupportAnswerMailSender(javaMailSender.getObject(), from);
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "prod 프로필에서는 문의 답변 메일 SMTP 설정이 필요합니다. SPRING_MAIL_HOST·PORT·USERNAME·PASSWORD 환경 변수를 넣으세요");
        }
        return new LoggingSupportAnswerMailSender();
    }
}
