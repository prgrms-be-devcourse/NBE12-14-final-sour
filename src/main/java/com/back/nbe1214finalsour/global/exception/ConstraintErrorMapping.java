package com.back.nbe1214finalsour.global.exception;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * DB 제약 이름 → {@link ErrorCode} (API 명세서 §1 "DB 제약 → 에러 코드" 표).
 *
 * <p>애플리케이션 검사를 통과한 동시 요청은 DB 제약 위반으로 돌아온다. 표에 있는 제약은 정해진 코드로 응답하고,
 * 표에 없는 제약(CHECK 등) 위반은 버그이므로 {@link GlobalExceptionHandler}가 500으로 응답한다.
 * 명세서 표가 바뀌면 이 파일만 고친다. 제약 이름 오타는 {@code ConstraintErrorMappingTest}가 마이그레이션과 대조해 잡는다.
 */
public final class ConstraintErrorMapping {

    private static final Map<String, ErrorCode> BY_CONSTRAINT = Map.of(
            "uk_users_email_hash", ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED,
            "uk_users_nickname_ci", ErrorCode.USER_NICKNAME_DUPLICATED,
            "fk_users_department", ErrorCode.USER_DEPARTMENT_MISMATCH,
            "uk_alliance_university_active", ErrorCode.ALLY_APPLICATION_PENDING,
            "uk_alliance_university_one_accepted", ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED,
            "uk_alliance_match_requested_pair", ErrorCode.MATCH_ALREADY_REQUESTED,
            "pk_guest_book_like", ErrorCode.GUEST_ALREADY_LIKED,
            "uk_credit_history_payment", ErrorCode.PAY_ALREADY_PROCESSED,
            "uk_credit_history_user_idempotency_key", ErrorCode.ITEM_DUPLICATE_PURCHASE);

    private ConstraintErrorMapping() {
    }

    public static Optional<ErrorCode> find(@Nullable String constraintName) {
        return constraintName == null ? Optional.empty() : Optional.ofNullable(BY_CONSTRAINT.get(constraintName));
    }

    public static Set<String> constraintNames() {
        return BY_CONSTRAINT.keySet();
    }
}
