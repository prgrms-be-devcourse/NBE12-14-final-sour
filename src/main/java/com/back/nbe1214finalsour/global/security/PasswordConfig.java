package com.back.nbe1214finalsour.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 인코더. 새 비밀번호는 BCrypt로 인코딩하고 앞에 {@code {bcrypt}}를 붙인다 (약 68자, {@code users.password}는 100자).
 * 알고리즘 접두어가 있어 나중에 알고리즘을 바꿔도 기존 비밀번호를 그대로 확인할 수 있다.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
