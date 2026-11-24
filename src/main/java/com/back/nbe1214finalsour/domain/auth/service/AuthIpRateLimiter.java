package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.repository.AuthIpLimitStore;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 로그인·인증 코드 요청의 IP 기준 한도 (SCRUM-100, 보안 점검 #3). 이메일 기준 한도만으로는 막지 못하는 남의 계정 잠그기,
 * 여러 계정에 흔한 비밀번호 대입, 인증 메일 대량 발송을 막는다.
 *
 * <p>IP는 컨트롤러가 {@code request.getRemoteAddr()}로 넘긴다. 프록시 뒤 실제 IP는 {@code server.forward-headers-strategy}가
 * 맞춘다 (운영 경로는 GitHub Issue #66). Redis 오류는 기존 이메일 한도처럼 그대로 올린다 (계획 Q7).
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthIpLimitProperties.class)
public class AuthIpRateLimiter {

    private final AuthIpLimitStore store;
    private final EmailHasher hasher;
    private final AuthIpLimitProperties properties;

    /**
     * 로그인 시도 자리를 잡는다. 비밀번호가 맞으면 {@link #releaseLogin}을 불러 실패 횟수에서 뺀다.
     *
     * @return {@link #releaseLogin}에 넘길 IP 해시
     * @throws BusinessException 429 {@code AUTH_TOO_MANY_REQUESTS}
     */
    public String acquireLogin(String clientIp) {
        String ipHash = hasher.hashClientIp(clientIp);
        if (!store.tryAcquireLogin(ipHash, properties.loginMaxFailures(), properties.loginWindow())) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS,
                    "IP 로그인 실패 " + properties.loginMaxFailures() + "번 초과");
        }
        return ipHash;
    }

    /** 비밀번호가 맞은 로그인은 실패로 세지 않는다 */
    public void releaseLogin(String ipHash) {
        store.releaseLogin(ipHash);
    }

    /** @throws BusinessException 429 {@code AUTH_TOO_MANY_REQUESTS} */
    public void checkEmailCode(String clientIp) {
        if (!store.tryAcquireEmailCode(hasher.hashClientIp(clientIp), properties.emailCodeMaxRequests(),
                properties.emailCodeWindow())) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS,
                    "IP 인증 코드 요청 " + properties.emailCodeMaxRequests() + "번 초과");
        }
    }
}
