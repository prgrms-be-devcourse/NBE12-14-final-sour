package com.back.nbe1214finalsour.domain.user.service;

import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.event.UserStatusChangedEvent;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 관리 (추가 명세 §3-9, SCRUM-93).
 *
 * <p>정지·차단하면 refresh 토큰을 모두 지우고, 커밋 뒤 {@link UserStatusChangedEvent}로 로그인 세션을 지운다
 * ({@code LoginSessionCleanupListener}). 그래서 남은 access 토큰도 바로 401이 되고, 클릭용 상태 캐시도 바뀐다
 * ({@code UserStatusCacheSyncListener}). 해제({@code ACTIVE})는 상태와 캐시만 바꾸고 로그인 상태는 건드리지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 같은 상태로 다시 바꿔도 같은 처리를 한다 (멱등).
     *
     * @param adminId 요청한 관리자 ID. 자기 자신은 바꿀 수 없다 (스스로 잠기는 것 방지)
     * @return 바뀐 뒤의 상태
     * @throws BusinessException 404 {@code USER_NOT_FOUND}: 없거나 탈퇴한 회원.
     *                           400 {@code COMMON_INVALID_INPUT}: {@code WITHDRAWN}으로 바꾸려 하거나 자기 자신
     */
    @Transactional
    public UserStatus changeStatus(Long adminId, Long userId, UserStatus status) {
        if (userId.equals(adminId)) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT, "관리자 자신의 상태 변경: adminId=" + adminId);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "없는 회원: " + userId));

        UserStatus previous = user.getStatus();
        // 탈퇴한 회원(404)·WITHDRAWN 요청(400)은 엔티티가 거절한다
        user.changeStatus(status);
        if (status != UserStatus.ACTIVE) {
            refreshTokenService.revokeAll(userId);
        }
        eventPublisher.publishEvent(new UserStatusChangedEvent(userId, status));
        log.info("회원 상태 변경 adminId={}, userId={}, {} -> {}", adminId, userId, previous, status);
        return status;
    }
}
