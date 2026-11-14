package com.back.nbe1214finalsour.domain.user.event;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;

/**
 * 회원 상태가 DB에서 바뀜 (탈퇴, 앞으로 관리자 정지·차단). 커밋 뒤 클릭용 Redis 상태 캐시를 맞추고
 * ({@code UserStatusCacheSyncListener}), {@code ACTIVE}가 아니면 로그인 세션을 지운다 ({@code LoginSessionCleanupListener}).
 * 트랜잭션 안에서 발행해야 한다 (두 리스너 모두 커밋 뒤에만 실행).
 */
public record UserStatusChangedEvent(Long userId, UserStatus status) {
}
