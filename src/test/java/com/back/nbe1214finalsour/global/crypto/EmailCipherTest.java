package com.back.nbe1214finalsour.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailCipherTest {

    /** Base64("test-only-email-encryption-key!!"), 32바이트 */
    static final String ENCRYPTION_KEY = "dGVzdC1vbmx5LWVtYWlsLWVuY3J5cHRpb24ta2V5ISE=";
    /** Base64("test-only-email-hash-key-not-for-prod"), 37바이트 */
    static final String HASH_KEY = "dGVzdC1vbmx5LWVtYWlsLWhhc2gta2V5LW5vdC1mb3ItcHJvZA==";

    private static final String EMAIL = "student@snu.ac.kr";

    private final EmailCipher cipher = new EmailCipher(new EmailCryptoProperties(ENCRYPTION_KEY, HASH_KEY));

    @Test
    @DisplayName("암호화한 값은 원문과 다르고, 복호화하면 원문으로 돌아온다")
    void roundTrip() {
        String encrypted = cipher.encrypt(EMAIL);

        assertThat(encrypted).isNotEqualTo(EMAIL).doesNotContain("student").doesNotContain("snu");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("같은 원문도 암호화할 때마다 IV가 달라 결과가 다르다")
    void randomIv() {
        assertThat(cipher.encrypt(EMAIL)).isNotEqualTo(cipher.encrypt(EMAIL));
    }

    @Test
    @DisplayName("저장 형식은 Base64(IV 12바이트 ‖ 암호문 ‖ 태그 16바이트)이고 users.email 길이 512자 안에 들어간다")
    void storedFormat() {
        String longestEmail = "a".repeat(64) + "@" + "b".repeat(185) + ".ac.kr"; // RFC 5321 최대 254자
        String encrypted = cipher.encrypt(longestEmail);

        byte[] raw = Base64.getDecoder().decode(encrypted);
        assertThat(raw).hasSize(12 + longestEmail.length() + 16);
        assertThat(encrypted.length()).isLessThanOrEqualTo(512);
    }

    @Test
    @DisplayName("암호문이 바뀌면 복호화하지 않고 예외를 던진다 (GCM 인증 태그)")
    void tamperedCiphertext() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt(EMAIL));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("다른 키로는 복호화할 수 없다")
    void wrongKey() {
        String otherKey = Base64.getEncoder().encodeToString("another-test-only-encryption-key".getBytes());
        EmailCipher other = new EmailCipher(new EmailCryptoProperties(otherKey, HASH_KEY));

        String encrypted = cipher.encrypt(EMAIL);

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("암호화 키가 32바이트가 아니거나 Base64가 아니면 생성에 실패한다 (애플리케이션 시작 실패)")
    void invalidKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new EmailCipher(new EmailCryptoProperties(shortKey, HASH_KEY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
        assertThatThrownBy(() -> new EmailCipher(new EmailCryptoProperties("not base64!", HASH_KEY)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
