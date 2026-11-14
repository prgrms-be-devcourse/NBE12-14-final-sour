package com.back.nbe1214finalsour.domain.auth.event;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.event.UserStatusChangedEvent;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 회원이 {@code ACTIVE}가 아니게 되면(탈퇴, 앞으로 관리자 정지·차단) 커밋 뒤 현재 로그인 세션을 지운다 (계정당 1기기, SCRUM-87).
 * 남은 access 토큰은 바로 {@code COMMON_EXPIRED_TOKEN}이 되고, 재발급도 회원 상태에서 거부된다. 롤백되면 세션은 그대로다.
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
        try {
            loginSessionStore.delete(event.userId());
        } catch (RuntimeException e) {
            // 상태 변경은 이미 커밋됐으므로 요청을 실패로 만들지 않는다. 남은 access 토큰은 만료(jwt.access-token-ttl)까지
            // 필터를 통과한다 (이 기능 전과 같음). 클릭은 상태 캐시가 막는다 (#47)
            log.error("로그인 세션 삭제 실패 userId={}, status={}", event.userId(), event.status(), e);
        }
    }
}
