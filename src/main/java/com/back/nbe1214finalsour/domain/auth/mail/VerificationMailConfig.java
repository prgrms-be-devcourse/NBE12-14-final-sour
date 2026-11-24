package com.back.nbe1214finalsour.domain.auth.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 인증 메일 발송 방식을 고른다.
 *
 * <ul>
 *   <li>{@code spring.mail.host}가 있으면 SMTP로 보낸다.</li>
 *   <li>없으면 {@code prod}가 아닌 프로필에서만 코드를 로그에 남기는 개발용 구현을 쓴다.</li>
 *   <li>{@code prod}에서 SMTP 설정이 없으면 앱이 시작하지 않는다. 메일 없이 배포되는 것을 막기 위함이다.</li>
 * </ul>
 *
 * <p>빈 정의를 하나로 둔다. 조건별로 {@code @Bean}을 나누면 IDE와 {@code @MockitoBean}이 타입 후보를 둘로 보고,
 * 일반 설정 클래스의 {@code @ConditionalOnMissingBean}은 빈 정의 처리 순서에 따라 결과가 달라질 수 있다.
 */
@Configuration(proxyBeanMethods = false)
public class VerificationMailConfig {

    static final String MAIL_HOST_PROPERTY = "spring.mail.host";

    @Bean
    public VerificationMailSender verificationMailSender(
            Environment environment,
            ObjectProvider<JavaMailSender> javaMailSender,
            @Value("${verification-mail.from:}") String from) {
        if (environment.containsProperty(MAIL_HOST_PROPERTY)) {
            return new SmtpVerificationMailSender(javaMailSender.getObject(), from);
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "prod 프로필에서는 인증 메일 SMTP 설정이 필요합니다. SPRING_MAIL_HOST·PORT·USERNAME·PASSWORD 환경 변수를 넣으세요");
        }
        return new LoggingVerificationMailSender();
    }
}
