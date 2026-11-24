package com.back.nbe1214finalsour.domain.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 닉네임 규칙 (API 명세서: 2~12자). 앞뒤 공백은 허용하지 않는다. 회원가입·닉네임 확인·프로필 수정이 같은 규칙을 쓴다.
 *
 * <p>{@code null}은 통과한다 (프로필 수정에서는 선택 입력). 필수인 곳은 {@code @NotNull}을 함께 붙인다.
 */
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@ReportAsSingleViolation
@Size(min = 2, max = 12)
@Pattern(regexp = "\\S(.*\\S)?")
public @interface Nickname {

    String message() default "닉네임은 앞뒤 공백 없이 2~12자여야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
