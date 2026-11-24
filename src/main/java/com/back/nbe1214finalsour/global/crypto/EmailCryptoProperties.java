package com.back.nbe1214finalsour.global.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 학교 이메일 암호화·해시 설정 ({@code email-crypto.*}). 두 키 모두 환경 변수로 넣고 저장소에 커밋하지 않는다.
 *
 * @param encryptionKey {@code users.email} AES-256-GCM 키. Base64로 인코딩한 정확히 32바이트 ({@code EMAIL_ENCRYPTION_KEY})
 * @param hashKey       {@code users.email_hash} HMAC-SHA256 키. Base64로 인코딩한 32바이트 이상 ({@code EMAIL_HASH_KEY}).
 *                      바꾸면 기존 회원을 이메일로 찾을 수 없다
 */
@Validated
@ConfigurationProperties("email-crypto")
public record EmailCryptoProperties(
        @NotBlank String encryptionKey,
        @NotBlank String hashKey) {
}
