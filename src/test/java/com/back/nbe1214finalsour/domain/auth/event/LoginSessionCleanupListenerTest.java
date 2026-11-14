package com.back.nbe1214finalsour.domain.auth.event;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.event.UserStatusChangedEvent;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class LoginSessionCleanupListenerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private LoginSessionStore loginSessionStore;

    @InjectMocks
    private LoginSessionCleanupListener listener;

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("ACTIVE가 아니게 되면(탈퇴·정지·차단) 세션 키를 지운다")
    void deletesSessionWhenNotActive(UserStatus status) {
        listener.onUserStatusChanged(new UserStatusChangedEvent(USER_ID, status));

        verify(loginSessionStore).delete(USER_ID);
    }

    @Test
    @DisplayName("ACTIVE로 바뀌면(정지 해제 등) 세션을 건드리지 않는다")
    void keepsSessionWhenActive() {
        listener.onUserStatusChanged(new UserStatusChangedEvent(USER_ID, UserStatus.ACTIVE));

        verifyNoInteractions(loginSessionStore);
    }

    @Test
    @DisplayName("Redis 실패는 로그만 남기고 던지지 않는다 (상태 변경은 이미 커밋됨)")
    void redisFailureDoesNotPropagate() {
        willThrow(new RedisConnectionFailureException("down")).given(loginSessionStore).delete(USER_ID);

        assertThatCode(() -> listener.onUserStatusChanged(new UserStatusChangedEvent(USER_ID, UserStatus.WITHDRAWN)))
                .doesNotThrowAnyException();
    }
}
