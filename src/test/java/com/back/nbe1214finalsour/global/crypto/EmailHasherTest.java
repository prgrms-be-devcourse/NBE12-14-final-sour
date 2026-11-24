package com.back.nbe1214finalsour.global.crypto;

import static com.back.nbe1214finalsour.global.crypto.EmailCipherTest.ENCRYPTION_KEY;
import static com.back.nbe1214finalsour.global.crypto.EmailCipherTest.HASH_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailHasherTest {

    private final EmailHasher hasher = new EmailHasher(new EmailCryptoProperties(ENCRYPTION_KEY, HASH_KEY));

    @Test
    @DisplayName("정규화한 이메일의 HMAC-SHA256을 소문자 hex 64자로 만든다")
    void hmacSha256Hex() {
        // printf 'student@snu.ac.kr' | openssl dgst -sha256 -mac HMAC -macopt key:'test-only-email-hash-key-not-for-prod'
        assertThat(hasher.hash("student@snu.ac.kr"))
                .isEqualTo("f54ae2a3e654eb5b5e1aaf79cfd3410c3eb0534d6ebf89dc78c9108e0fd14128")
                .matches("^[0-9a-f]{64}$"); // DB 제약 ck_users_email_hash
    }

    @Test
    @DisplayName("앞뒤 공백과 대소문자가 달라도 같은 해시가 나온다")
    void normalizesBeforeHashing() {
        assertThat(hasher.hash("  Student@SNU.ac.KR ")).isEqualTo(hasher.hash("student@snu.ac.kr"));
    }

    @Test
    @DisplayName("정규화는 앞뒤 공백 제거 + 전체 소문자다")
    void normalize() {
        assertThat(EmailHasher.normalize("  Student@SNU.ac.KR ")).isEqualTo("student@snu.ac.kr");
    }

    @Test
    @DisplayName("키가 다르면 해시도 달라 키 없이 이메일을 대입해 볼 수 없다")
    void keyed() {
        String otherKey = Base64.getEncoder().encodeToString("another-test-only-hash-key-0123456789".getBytes());
        EmailHasher other = new EmailHasher(new EmailCryptoProperties(ENCRYPTION_KEY, otherKey));

        assertThat(other.hash("student@snu.ac.kr")).isNotEqualTo(hasher.hash("student@snu.ac.kr"));
    }

    @Test
    @DisplayName("클라이언트 IP는 'ip:' 접두어를 붙여 해시해 같은 문자열의 이메일 해시와 겹치지 않는다 (hex 64자, IPv6 대소문자 무시)")
    void hashClientIp() {
        assertThat(hasher.hashClientIp("203.0.113.7"))
                .matches("^[0-9a-f]{64}$")
                .isNotEqualTo(hasher.hash("203.0.113.7"))
                .isNotEqualTo(hasher.hashClientIp("203.0.113.8"));
        assertThat(hasher.hashClientIp("2001:DB8::1")).isEqualTo(hasher.hashClientIp("2001:db8::1"));
    }

    @Test
    @DisplayName("해시 키가 32바이트 미만이면 생성에 실패한다 (애플리케이션 시작 실패)")
    void shortKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new EmailHasher(new EmailCryptoProperties(ENCRYPTION_KEY, shortKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }
}
