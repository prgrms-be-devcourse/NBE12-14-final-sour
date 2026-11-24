package com.back.nbe1214finalsour.domain.support.mail;

/**
 * 문의 답변 메일을 보낸다 (SCRUM-113). 구현은 {@link SupportAnswerMailConfig}가 고른다.
 *
 * <p>보내지 못하면 런타임 예외를 던진다. 호출한 쪽({@code SupportAnswerMailListener})은 로그만 남긴다.
 */
public interface SupportAnswerMailSender {

    /**
     * @param recipientEmail 문의할 때 받은 수신 이메일 (복호화한 원문)
     * @param supportTitle   원래 문의 제목
     */
    void sendAnswer(String recipientEmail, String supportTitle, String answerTitle, String answerContent);
}
