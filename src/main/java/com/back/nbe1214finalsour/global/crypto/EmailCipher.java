package com.back.nbe1214finalsour.global.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 학교 이메일을 AES-256-GCM으로 암호화·복호화한다 ({@code users.email}).
 *
 * <p>저장 형식은 {@code Base64(IV 12바이트 ‖ 암호문 ‖ 인증 태그 16바이트)}이다. IV는 암호화할 때마다 새로 만들어
 * 같은 이메일도 매번 다른 값이 된다. 그래서 이메일로 회원을 찾을 때는 이 값이 아니라 {@link EmailHasher}의 해시를 쓴다.
 */
@Component
@EnableConfigurationProperties(EmailCryptoProperties.class)
public class EmailCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EmailCipher(EmailCryptoProperties properties) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.encryptionKey());
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "email-crypto.encryption-key는 Base64로 인코딩한 32바이트여야 합니다 (현재 " + keyBytes.length + "바이트)");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_BYTES + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("이메일을 암호화하지 못했습니다", e);
        }
    }

    /**
     * @throws IllegalStateException 형식이 틀렸거나, 값이 바뀌었거나, 다른 키로 암호화한 값이면
     */
    public String decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            if (raw.length <= IV_BYTES) {
                throw new IllegalStateException("암호화한 이메일의 길이가 너무 짧습니다");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 예외 메시지에 저장값을 넣지 않는다
            throw new IllegalStateException("암호화한 이메일을 복호화하지 못했습니다", e);
        }
    }
}
