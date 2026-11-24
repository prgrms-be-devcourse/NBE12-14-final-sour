package com.back.nbe1214finalsour.domain.chat.service;

import com.back.nbe1214finalsour.domain.chat.dto.ChatMessagePageResponse;
import com.back.nbe1214finalsour.domain.chat.entity.ChatRoomId;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** 채팅 이전 메시지 조회 (SCRUM-131, 계획 D8) */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    /** 한 번에 읽는 개수의 상한이자 기본값 (D8) */
    public static final int MAX_PAGE_SIZE = 20;

    private final ChatMessageRepository chatMessageRepository;
    private final AllianceChatMembership allianceChatMembership;

    /**
     * 소속 학교 방만 읽을 수 있다. 소속은 구독 권한과 같이 토큰의 학교 ID로 판단한다.
     *
     * @throws BusinessException {@code CHAT_NOT_MEMBER}
     */
    public ChatMessagePageResponse getUniversityMessages(AuthUser authUser, long universityId, @Nullable String cursor,
                                                         int size) {
        if (authUser.universityId() == null || authUser.universityId() != universityId) {
            throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER);
        }
        return ChatMessagePageResponse.from(
                this.chatMessageRepository.findLatest(ChatRoomId.university(universityId), cursor, size));
    }

    /**
     * 지금 그 연합에 속한 학교의 회원만 읽을 수 있다. 탈퇴·만료 뒤에는 이전 메시지도 읽지 못한다 (D3).
     *
     * @throws BusinessException {@code CHAT_NOT_MEMBER}
     */
    public ChatMessagePageResponse getAllianceMessages(AuthUser authUser, long allianceId, @Nullable String cursor,
                                                       int size) {
        this.allianceChatMembership.requireMember(authUser, allianceId);
        return ChatMessagePageResponse.from(
                this.chatMessageRepository.findLatest(ChatRoomId.alliance(allianceId), cursor, size));
    }
}
