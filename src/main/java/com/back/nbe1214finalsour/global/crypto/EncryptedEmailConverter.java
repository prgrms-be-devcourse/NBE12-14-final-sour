package com.back.nbe1214finalsour.global.crypto;

import jakarta.persistence.AttributeConverter;
import org.springframework.stereotype.Component;

/**
 * 엔티티의 이메일 필드를 저장할 때 암호화하고 읽을 때 복호화한다. {@code @Convert(converter = EncryptedEmailConverter.class)}로 쓴다.
 *
 * <p>Spring Boot가 Hibernate에 Spring 빈 컨테이너를 연결하므로, Hibernate는 이 클래스를 직접 만들지 않고 빈을 받아 쓴다.
 */
@Component
public class EncryptedEmailConverter implements AttributeConverter<String, String> {

    private final EmailCipher cipher;

    public EncryptedEmailConverter(EmailCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String email) {
        return email == null ? null : cipher.encrypt(email);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return stored == null ? null : cipher.decrypt(stored);
    }
}
