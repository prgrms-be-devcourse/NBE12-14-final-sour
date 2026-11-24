package com.back.nbe1214finalsour.domain.chat.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.chat.dto.ChatMessageResponse;
import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import com.back.nbe1214finalsour.domain.chat.entity.ChatRoomId;
import com.back.nbe1214finalsour.domain.chat.relay.ChatMessagePublisher;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import com.back.nbe1214finalsour.domain.forbiddenword.service.ForbiddenWordFilter;
import com.back.nbe1214finalsour.domain.university.repository.UniversityCache;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import com.back.nbe1214finalsour.global.security.AuthUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 대학·연합 채팅 메시지 보내기 (SCRUM-130, SCRUM-182, 계획 §5 4단계).
 *
 * <p>순서: 길이 검사(D13) → 회원 상태(D2) → 1초 1개 한도(D11) → 금칙어 가리기(D12) → 저장 → 발행(D7).
 * 길이를 먼저 보는 이유는 잘못된 본문이 한도를 쓰지 않게 하려는 것이다. 원문은 저장하지 않는다(D21).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    /** 대학 채팅 보관 기간 (D9) */
    public static final Duration UNIVERSITY_RETENTION = Duration.ofDays(7);
    /** 본문 길이. 글자 수는 코드 포인트로 센다(이모지 하나 = 1자) */
    public static final int MAX_CONTENT_LENGTH = 200;
    /** 회원당 1초에 1개 (D11) */
    static final Duration SEND_INTERVAL = Duration.ofSeconds(1);

    private final UserRepository userRepository;
    private final UniversityCache universityCache;
    private final ForbiddenWordFilter forbiddenWordFilter;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessagePublisher chatMessagePublisher;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final AllianceChatMembership allianceChatMembership;

    /**
     * 로그인 회원의 소속 학교 방으로 보낸다. 방은 요청이 아니라 토큰의 학교 ID로 정한다.
     *
     * @throws BusinessException {@code CHAT_INVALID_CONTENT}, {@code USER_SUSPENDED}, {@code CHAT_TOO_FREQUENT},
     *                           저장 실패 {@code COMMON_INTERNAL_ERROR}(D18)
     */
    public ChatMessageResponse sendToUniversity(AuthUser authUser, @Nullable String content) {
        String text = validContent(content);
        User user = activeUser(authUser.userId());
        acquireSendSlot(authUser.userId());

        long universityId = authUser.universityId();
        ChatMessage message = ChatMessage.create(ChatRoomId.university(universityId), user.getId(), user.getNickname(),
                this.universityCache.getName(universityId), this.forbiddenWordFilter.mask(text),
                this.clock.instant(), UNIVERSITY_RETENTION);
        return saveAndPublish(message, response -> this.chatMessagePublisher.publishToUniversity(universityId, response));
    }

    /**
     * 내 학교가 지금 속한 연합 방으로 보낸다 (SCRUM-182). 방은 요청이 아니라 DB의 현재 소속으로 정한다.
     * 소속 검사를 1초 한도 앞에 두어, 연합이 없는 회원의 요청이 한도를 쓰지 않게 한다. 메시지는 연합 만료 시각에 지워진다(TTL).
     *
     * @throws BusinessException {@code CHAT_INVALID_CONTENT}, {@code USER_SUSPENDED}, {@code CHAT_NOT_MEMBER},
     *                           {@code CHAT_TOO_FREQUENT}, 저장 실패 {@code COMMON_INTERNAL_ERROR}(D18)
     */
    public ChatMessageResponse sendToAlliance(AuthUser authUser, @Nullable String content) {
        String text = validContent(content);
        User user = activeUser(authUser.userId());
        Alliance alliance = this.allianceChatMembership.requireCurrent(authUser);
        acquireSendSlot(authUser.userId());

        long allianceId = alliance.getId();
        Instant now = this.clock.instant();
        ChatMessage message = ChatMessage.create(ChatRoomId.alliance(allianceId), user.getId(), user.getNickname(),
                this.universityCache.getName(authUser.universityId()), this.forbiddenWordFilter.mask(text),
                now, Duration.between(now, alliance.getExpiredAt().toInstant()));
        return saveAndPublish(message, response -> this.chatMessagePublisher.publishToAlliance(allianceId, response));
    }

    private ChatMessageResponse saveAndPublish(ChatMessage message, Consumer<ChatMessageResponse> publish) {
        try {
            this.chatMessageRepository.save(message);
        } catch (RuntimeException e) {
            log.error("채팅 메시지 저장 실패 roomId={} userId={}", message.getRoomId(), message.getUserId(), e);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
        }

        ChatMessageResponse response = ChatMessageResponse.from(message);
        try {
            publish.accept(response);
        } catch (RuntimeException e) {
            // 저장은 됐으므로 보낸 사람에게 에러를 보내지 않는다. 다른 사람은 이전 메시지 조회로 본다 (D22)
            log.error("채팅 메시지 발행 실패 roomId={} messageId={}", message.getRoomId(), message.getMessageId(), e);
        }
        return response;
    }

    private static String validContent(@Nullable String content) {
        if (content == null) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_CONTENT);
        }
        String text = content.strip();
        int length = text.codePointCount(0, text.length());
        if (length == 0 || length > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.CHAT_INVALID_CONTENT);
        }
        return text;
    }

    /** 연결 중에 정지·탈퇴됐을 수 있으므로 보낼 때마다 DB에서 다시 본다 (D2). 닉네임도 보낸 시점 값을 쓴다 */
    private User activeUser(Long userId) {
        User user = this.userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        return user;
    }

    private void acquireSendSlot(Long userId) {
        Boolean acquired = this.redisTemplate.opsForValue()
                .setIfAbsent(RedisKey.RATELIMIT_CHAT.of(userId), "1", SEND_INTERVAL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.CHAT_TOO_FREQUENT);
        }
    }
}
