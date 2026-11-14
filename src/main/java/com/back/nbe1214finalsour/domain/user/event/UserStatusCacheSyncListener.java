package com.back.nbe1214finalsour.domain.user.event;

import com.back.nbe1214finalsour.domain.user.repository.UserStatusCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 회원 상태 변경이 DB에 커밋된 뒤 클릭용 Redis 상태 캐시를 덮어쓴다. 롤백되면 캐시는 그대로다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusCacheSyncListener {

    private final UserStatusCache userStatusCache;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        try {
            userStatusCache.put(event.userId(), event.status());
        } catch (RuntimeException e) {
            // 상태 변경은 이미 커밋됐으므로 요청을 실패로 만들지 않는다. 캐시는 TTL(jwt.access-token-ttl) 뒤 DB 값으로 돌아온다
            log.error("회원 상태 캐시 갱신 실패 userId={}, status={}", event.userId(), event.status(), e);
        }
    }
}
