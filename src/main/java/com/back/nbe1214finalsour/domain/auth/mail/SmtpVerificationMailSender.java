package com.back.nbe1214finalsour.domain.auth.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** {@code spring.mail.*}로 설정한 SMTP 서버로 인증 코드를 보낸다. 요청 처리 중에 동기로 보낸다. */
public class SmtpVerificationMailSender implements VerificationMailSender {

    static final String SUBJECT = "[대대대] 학교 이메일 인증 코드";

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param from 발신 주소. 비어 있으면 넣지 않고 SMTP 서버의 기본값을 쓴다
     */
    public SmtpVerificationMailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(email);
        message.setSubject(SUBJECT);
        message.setText("""
                대대대 학교 이메일 인증 코드입니다.

                인증 코드: %s

                코드는 5분 동안 유효합니다. 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(code));
        mailSender.send(message);
    }
}
