package com.back.nbe1214finalsour.domain.user.event;

/**
 * 회원 비밀번호가 DB에서 바뀜 ({@code UserService.changePassword}, SCRUM-98). 커밋 뒤 로그인 세션을 지워
 * ({@code LoginSessionCleanupListener}) 이미 발급된 access 토큰도 다음 요청부터 401이 되게 한다.
 * 트랜잭션 안에서 발행해야 한다 (리스너는 커밋 뒤에만 실행).
 */
public record PasswordChangedEvent(Long userId) {
}
