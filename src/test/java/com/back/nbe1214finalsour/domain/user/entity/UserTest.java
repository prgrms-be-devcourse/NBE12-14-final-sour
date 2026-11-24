package com.back.nbe1214finalsour.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UserTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "SUSPENDED", "BANNED"})
    @DisplayName("관리자 상태 변경: ACTIVE·SUSPENDED·BANNED로 바꿀 수 있다")
    void changeStatus(UserStatus status) {
        User user = newUser();
        user.changeStatus(UserStatus.SUSPENDED);

        user.changeStatus(status);

        assertThat(user.getStatus()).isEqualTo(status);
        assertThat(user.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("관리자 상태 변경으로 탈퇴시킬 수 없다: 400 COMMON_INVALID_INPUT (탈퇴는 deleted_at이 함께 필요하다)")
    void cannotChangeToWithdrawn() {
        User user = newUser();

        assertThatThrownBy(() -> user.changeStatus(UserStatus.WITHDRAWN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMON_INVALID_INPUT));
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("탈퇴한 회원의 상태는 바꿀 수 없다: 404 USER_NOT_FOUND")
    void cannotChangeWithdrawnUser() {
        User user = newUser();
        user.withdraw(OffsetDateTime.now());

        assertThatThrownBy(() -> user.changeStatus(UserStatus.ACTIVE))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    private static User newUser() {
        return User.create(1L, 2L, "닉네임", "student@test.ac.kr", "h".repeat(64), "{bcrypt}x", OffsetDateTime.now());
    }
}
