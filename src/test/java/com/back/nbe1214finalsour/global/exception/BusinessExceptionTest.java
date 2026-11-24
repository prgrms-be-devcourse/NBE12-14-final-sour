package com.back.nbe1214finalsour.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    @DisplayName("ErrorCode만 주면 예외 메시지는 ErrorCode의 메시지다")
    void messageDefaultsToErrorCodeMessage() {
        BusinessException exception = new BusinessException(ErrorCode.COMMON_FORBIDDEN);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_FORBIDDEN);
        assertThat(exception.getMessage()).isEqualTo("권한이 없습니다.");
    }

    @Test
    @DisplayName("detail을 주면 예외 메시지(로그용)만 바뀌고 ErrorCode는 유지된다")
    void detailOverridesOnlyExceptionMessage() {
        BusinessException exception = new BusinessException(ErrorCode.COMMON_FORBIDDEN, "userId=3 adminOnly");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMON_FORBIDDEN);
        assertThat(exception.getMessage()).isEqualTo("userId=3 adminOnly");
    }
}
