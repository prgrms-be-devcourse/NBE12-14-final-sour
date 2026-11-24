package com.back.nbe1214finalsour.domain.chat.service;

import com.back.nbe1214finalsour.domain.chat.websocket.ChatDestinations;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 수신자별 handler 실행 직전의 현재 DB 권한. 승인 캐시나 발행자 정보로 대체하지 않는다. */
@Component
public class ChatReceiveAuthorization {

    private static final Pattern UNIVERSITY = Pattern.compile("^" + ChatDestinations.UNIVERSITY_TOPIC_PREFIX + "(\\d{1,18})$");
    private static final Pattern ALLIANCE = Pattern.compile("^" + ChatDestinations.ALLIANCE_TOPIC_PREFIX + "(\\d{1,18})$");
    private final UserRepository users;
    private final AllianceChatMembership membership;

    public ChatReceiveAuthorization(UserRepository users, AllianceChatMembership membership) {
        this.users = users;
        this.membership = membership;
    }

    public void requireAllowed(AuthUser recipient, String destination) {
        if (!this.users.existsByIdAndUniversityIdAndStatusAndDeletedAtIsNull(
                recipient.userId(), recipient.universityId(), UserStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER);
        }
        Matcher university = UNIVERSITY.matcher(destination);
        if (university.matches() && Long.parseLong(university.group(1)) == recipient.universityId()) {
            return;
        }
        Matcher alliance = ALLIANCE.matcher(destination);
        if (alliance.matches()) {
            this.membership.requireMember(recipient, Long.parseLong(alliance.group(1)));
            return;
        }
        throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER);
    }
}
