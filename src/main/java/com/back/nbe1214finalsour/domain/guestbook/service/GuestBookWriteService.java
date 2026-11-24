package com.back.nbe1214finalsour.domain.guestbook.service;

import com.back.nbe1214finalsour.domain.forbiddenword.service.ForbiddenWordFilter;
import com.back.nbe1214finalsour.domain.guestbook.dto.GuestBookListResponse;
import com.back.nbe1214finalsour.domain.guestbook.entity.GuestBook;
import com.back.nbe1214finalsour.domain.guestbook.repository.GuestBookRepository;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.redis.RedisKey;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestBookWriteService {

    private static final int MAX_CONTENT_LENGTH = 500;
    private static final Duration WRITE_INTERVAL = Duration.ofMinutes(1);

    private final GuestBookRepository guestBookRepository;
    private final UniversityRepository universityRepository;
    private final UserRepository userRepository;
    private final ForbiddenWordFilter forbiddenWordFilter;
    private final StringRedisTemplate redisTemplate;

    /**
     * 로그인한 회원이 다른 대학 방명록에 글을 남긴다. 회원 소속은 토큰의 값보다 DB의 최신 상태를 우선한다.
     * Redis SET NX + TTL로 동시 요청도 회원당 1분에 1개만 허용한다.
     * Redis 슬롯을 점유한 뒤 DB 저장이 실패하면 제한은 최대 1분간 유지된다(채팅과 같은 정책).
     */
    @Transactional
    public GuestBookListResponse.Item create(Long targetUniversityId, Long authorUserId, @Nullable String content) {
        String text = validateContent(content);

        User author = userRepository.findById(authorUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (author.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        if (!universityRepository.existsById(targetUniversityId)) {
            throw new BusinessException(ErrorCode.UNIV_NOT_FOUND);
        }
        if (author.getUniversityId().equals(targetUniversityId)) {
            throw new BusinessException(ErrorCode.GUEST_OWN_UNIVERSITY);
        }

        University authorUniversity = universityRepository.findById(author.getUniversityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNIV_NOT_FOUND));
        String masked = forbiddenWordFilter.mask(text, "***");
        // 금칙어 1글자가 ***로 늘어나면 VARCHAR(500) 크기를 초과할 수 있다.
        if (masked.codePointCount(0, masked.length()) > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT);
        }

        String rateLimitKey = RedisKey.RATELIMIT_GUESTBOOK.of(authorUserId);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(rateLimitKey, "1", WRITE_INTERVAL);
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(ErrorCode.GUEST_TOO_FREQUENT);
        }

        // saveAndFlush: DB 기본값으로 생성된 createdAt까지 채워 응답한다.
        GuestBook saved = guestBookRepository.saveAndFlush(
                GuestBook.create(authorUserId, targetUniversityId, masked));
        return new GuestBookListResponse.Item(saved.getId(), saved.getContent(), saved.getLikeCount(),
                false, new GuestBookListResponse.Author(author.getNickname(), authorUniversity.getName()),
                saved.getCreatedAt());
    }

    private static String validateContent(@Nullable String content) {
        if (content == null) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT);
        }
        String text = content.strip();
        int length = text.codePointCount(0, text.length());
        if (length < 1 || length > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT);
        }
        return text;
    }
}
