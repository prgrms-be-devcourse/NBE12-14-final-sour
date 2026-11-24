package com.back.nbe1214finalsour.domain.auth.event;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.event.PasswordChangedEvent;
import com.back.nbe1214finalsour.domain.user.event.UserStatusChangedEvent;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 뒤 현재 로그인 세션을 지운다 (계정당 1기기). 남은 access 토큰은 바로 {@code COMMON_EXPIRED_TOKEN}이 된다.
 * 롤백되면 세션은 그대로다.
 *
 * <ul>
 *   <li>회원이 {@code ACTIVE}가 아니게 됨(탈퇴, 관리자 정지·차단, SCRUM-87). 재발급도 회원 상태에서 거부된다</li>
 *   <li>비밀번호 변경 (SCRUM-98). refresh 토큰은 이미 모두 지워져 재발급도 401이고, 새 비밀번호로 다시 로그인한다</li>
 * </ul>
 *
 * <p>Redis 실패는 로그만 남긴다. 상태·비밀번호 변경은 이미 커밋됐으므로 요청을 실패로 만들지 않는다.
 * 그때 남은 access 토큰은 만료({@code jwt.access-token-ttl})까지 필터를 통과한다 (이 기능 전과 같음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSessionCleanupListener {

    private final LoginSessionStore loginSessionStore;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        if (event.status() == UserStatus.ACTIVE) {
            return;
        }
        // 클릭은 Redis 실패 때도 상태 캐시가 막는다 (#47)
        deleteSession(event.userId(), "status=" + event.status());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedEvent event) {
        deleteSession(event.userId(), "비밀번호 변경");
    }

    private void deleteSession(Long userId, String reason) {
        try {
            loginSessionStore.delete(userId);
        } catch (RuntimeException e) {
            log.error("로그인 세션 삭제 실패 userId={}, {}", userId, reason, e);
        }
    }
}
