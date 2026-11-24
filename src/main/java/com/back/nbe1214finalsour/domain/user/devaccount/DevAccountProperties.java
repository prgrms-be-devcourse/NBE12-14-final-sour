package com.back.nbe1214finalsour.domain.user.devaccount;

import com.back.nbe1214finalsour.domain.user.validation.Nickname;
import com.back.nbe1214finalsour.global.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 개발자 공용 계정 ({@code dev-account.*}, SCRUM-237, GitHub #142). 앱이 시작될 때 계정이 없으면 만든다.
 * 기본값은 application.yaml(로컬: 켜짐·ADMIN), application-prod.yaml(운영: 꺼짐·USER)에 있다.
 *
 * @param enabled        false면 아무것도 하지 않는다 ({@code DEV_ACCOUNT_ENABLED})
 * @param email          로그인 이메일 ({@code DEV_ACCOUNT_EMAIL}). 기본값이 없고, 비어 있으면 계정을 만들지 않는다.
 *                       실제 주소는 저장소에 올리지 않는다. 학교 도메인 검사를 받지 않는다
 * @param password       원문 비밀번호 ({@code DEV_ACCOUNT_PASSWORD}). 기본값이 없고, 비어 있으면 계정을 만들지 않는다.
 *                       저장소·채팅에 올리지 않는다. 회원가입과 같은 비밀번호 규칙을 지켜야 한다
 * @param nickname       닉네임. 회원가입과 같은 규칙
 * @param universityName 소속 학교 이름. 기본 와플대학교(V14 숨김 학교). ID는 DB마다 다를 수 있어 이름으로 찾는다
 * @param departmentName 소속 학과 이름. 기본 운영팀(V14)
 * @param role           권한 ({@code DEV_ACCOUNT_ROLE})
 */
@Validated
@ConfigurationProperties("dev-account")
public record DevAccountProperties(
        boolean enabled,
        @Email String email,
        String password,
        @NotNull @Nickname String nickname,
        @NotBlank String universityName,
        @NotBlank String departmentName,
        @NotNull Role role) {

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    /** 비밀번호와 이메일을 로그에 남기지 않는다 */
    @Override
    public String toString() {
        return "DevAccountProperties[enabled=" + enabled + ", email=***, password=***, nickname=" + nickname
                + ", universityName=" + universityName + ", departmentName=" + departmentName + ", role=" + role + "]";
    }
}
