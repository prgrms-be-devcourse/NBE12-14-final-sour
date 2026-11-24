package com.back.nbe1214finalsour.domain.chat.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.service.AllianceQueryService;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 연합 채팅방 소속 판정 (SCRUM-182, 결정 D3). 연합 소속은 토큰에 없고 수시로 바뀌므로 구독·이력·전송 때마다 DB에서 본다.
 * 소속 기준은 {@link AllianceQueryService#findCurrentAlliance}(승인된 소속, 만료·삭제되지 않은 연합)와 같다.
 */
@Component
@RequiredArgsConstructor
public class AllianceChatMembership {

    private final AllianceQueryService allianceQueryService;

    /** 내 학교가 지금 속한 연합. 없으면 {@code CHAT_NOT_MEMBER} */
    public Alliance requireCurrent(AuthUser user) {
        return this.allianceQueryService.findCurrentAlliance(user.universityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_NOT_MEMBER,
                        "소속 연합 없음 userId=" + user.userId()));
    }

    /** 내 학교가 지금 그 연합에 속하지 않으면 {@code CHAT_NOT_MEMBER} */
    public void requireMember(AuthUser user, long allianceId) {
        Alliance current = requireCurrent(user);
        if (current.getId() != allianceId) {
            throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER,
                    "다른 연합 방 userId=" + user.userId() + ", allianceId=" + allianceId);
        }
    }
}
