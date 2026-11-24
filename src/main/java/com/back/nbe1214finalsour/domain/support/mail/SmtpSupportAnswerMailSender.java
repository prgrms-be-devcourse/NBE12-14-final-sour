package com.back.nbe1214finalsour.domain.support.mail;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * {@code spring.mail.*}로 설정한 SMTP 서버로 문의 답변을 평문 메일로 보낸다 (계획 D7). 발신 주소는 인증 메일과 같은
 * {@code verification-mail.from}이다 (계획 D8).
 */
public class SmtpSupportAnswerMailSender implements SupportAnswerMailSender {

    static final String SUBJECT_PREFIX = "[대대대] 문의 답변: ";

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * @param from 발신 주소. 비어 있으면 넣지 않고 SMTP 서버의 기본값을 쓴다
     */
    public SmtpSupportAnswerMailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendAnswer(String recipientEmail, String supportTitle, String answerTitle, String answerContent) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(recipientEmail);
        // 제목은 한 줄이어야 한다. 관리자가 넣은 줄바꿈은 공백으로 바꾼다
        message.setSubject(SUBJECT_PREFIX + answerTitle.replaceAll("[\\r\\n]+", " "));
        message.setText("""
                대대대에 남겨 주신 문의에 답변드립니다.

                문의 제목: %s

                %s
                """.formatted(supportTitle, answerContent));
        mailSender.send(message);
    }
}
