package com.back.nbe1214finalsour.domain.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 새로 정하는 비밀번호의 규칙: 8~64자, 공백 없는 ASCII 문자만, 영문·숫자·특수문자를 각각 하나 이상.
 * ASCII로 제한해 BCrypt 입력 한도(72바이트)를 넘지 않게 한다. 회원가입과 비밀번호 변경이 같은 규칙을 쓴다.
 *
 * <p>로그인·현재 비밀번호 확인처럼 이미 정한 비밀번호를 받는 곳에는 쓰지 않는다 (규칙이 바뀌어도 기존 회원이 로그인할 수 있게).
 */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@NotNull
@Pattern(regexp = "(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{8,64}")
public @interface Password {

    String message() default "비밀번호는 공백 없이 8~64자이고 영문·숫자·특수문자를 모두 포함해야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
