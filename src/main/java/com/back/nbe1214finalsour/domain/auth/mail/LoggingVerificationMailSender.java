package com.back.nbe1214finalsour.domain.auth.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * 개발용. 메일을 보내지 않고 인증 코드를 로그에 남긴다. SMTP 설정이 없는 로컬 환경에서 가입 흐름을 시험하는 용도다.
 *
 * <p>로그를 볼 수 있는 사람은 누구의 이메일이든 인증할 수 있으므로 {@code prod} 프로필에서는 쓰지 않는다
 * ({@link VerificationMailConfig}).
 */
@Slf4j
public class LoggingVerificationMailSender implements VerificationMailSender {

    @Override
    public void sendCode(String email, String code) {
        log.info("[개발용] 인증 메일을 보내지 않고 로그로 대신합니다: email={}, code={}", email, code);
    }
}
