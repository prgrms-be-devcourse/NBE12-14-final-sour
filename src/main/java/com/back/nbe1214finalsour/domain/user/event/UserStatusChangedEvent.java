package com.back.nbe1214finalsour.domain.user.event;

import com.back.nbe1214finalsour.domain.user.entity.UserStatus;

/**
 * 회원 상태가 DB에서 바뀜 (탈퇴, 앞으로 관리자 정지·차단). 커밋 뒤 클릭용 Redis 상태 캐시를 맞추는 데 쓴다.
 * 트랜잭션 안에서 발행해야 한다 ({@code UserStatusCacheSyncListener}는 커밋 뒤에만 실행).
 */
public record UserStatusChangedEvent(Long userId, UserStatus status) {
}
