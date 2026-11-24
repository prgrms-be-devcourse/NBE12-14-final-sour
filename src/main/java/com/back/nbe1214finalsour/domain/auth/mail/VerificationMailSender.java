package com.back.nbe1214finalsour.domain.auth.mail;

/**
 * 학교 이메일 인증 코드를 보낸다. 구현은 {@link VerificationMailConfig}가 고른다.
 *
 * <p>보내지 못하면 런타임 예외를 던진다. 호출한 쪽은 저장한 코드를 지우고 요청을 실패시킨다.
 */
public interface VerificationMailSender {

    /**
     * @param email 정규화한 수신 이메일
     * @param code  6자리 인증 코드
     */
    void sendCode(String email, String code);
}
