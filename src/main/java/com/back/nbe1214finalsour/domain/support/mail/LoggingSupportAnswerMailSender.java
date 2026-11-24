package com.back.nbe1214finalsour.domain.support.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * 개발용. 메일을 보내지 않고 보냈다는 사실만 로그에 남긴다. 수신 이메일과 답변 내용은 남기지 않는다 (계획 D9).
 * {@code prod} 프로필에서는 쓰지 않는다 ({@link SupportAnswerMailConfig}).
 */
@Slf4j
public class LoggingSupportAnswerMailSender implements SupportAnswerMailSender {

    @Override
    public void sendAnswer(String recipientEmail, String supportTitle, String answerTitle, String answerContent) {
        log.info("[개발용] 문의 답변 메일을 보내지 않고 로그로 대신합니다 (SMTP 설정 없음)");
    }
}
