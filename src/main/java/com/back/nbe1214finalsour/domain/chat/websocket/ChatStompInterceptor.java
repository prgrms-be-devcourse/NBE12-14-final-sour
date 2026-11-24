package com.back.nbe1214finalsour.domain.chat.websocket;

import com.back.nbe1214finalsour.domain.chat.service.AllianceChatMembership;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.jwt.AccessTokenAuthenticator;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * 클라이언트가 보낸 STOMP 프레임의 인증과 목적지 권한을 검사한다 (SCRUM-128, 계획 D1·§3-1).
 *
 * <ul>
 *   <li>CONNECT: {@code Authorization: Bearer …} 헤더를 HTTP와 같은 규칙({@link AccessTokenAuthenticator})으로 검증하고
 *       세션에 {@link StompUser}를 붙인다. 연결 중 토큰이 만료돼도 연결은 유지한다(D2)</li>
 *   <li>SUBSCRIBE: 본인 학교 방({@code /topic/chat/universities/{내 학교}}), 내 학교가 지금 속한 연합 방
 *       ({@code /topic/chat/alliances/{연합}}, DB에서 확인, SCRUM-182)과 내 에러 큐({@code /user/queue/errors})만 허용한다.
 *       다른 학교·연합 방은 {@code CHAT_NOT_MEMBER}</li>
 *   <li>SEND: {@code /app/**}만 허용한다. 막지 않으면 클라이언트가 {@code /topic/…}에 직접 보내 저장·검사 없이 방 전체에 뿌릴 수 있다</li>
 * </ul>
 * 목록에 없는 목적지는 모두 {@code COMMON_FORBIDDEN}이다 (HTTP의 {@code denyAll}과 같은 방식).
 *
 * <p>여기서 던진 예외는 {@link StompErrorFrameHandler}가 ERROR 프레임으로 바꾸고, Spring이 연결을 끊는다.
 */
@Component
public class ChatStompInterceptor implements ChannelInterceptor {

    private static final Pattern UNIVERSITY_TOPIC = Pattern.compile("^/topic/chat/universities/(\\d{1,18})$");
    private static final Pattern ALLIANCE_TOPIC = Pattern.compile("^/topic/chat/alliances/(\\d{1,18})$");

    private final AccessTokenAuthenticator accessTokenAuthenticator;
    private final AllianceChatMembership allianceChatMembership;

    public ChatStompInterceptor(JwtProvider jwtProvider, LoginSessionStore loginSessionStore,
                                AllianceChatMembership allianceChatMembership) {
        this.accessTokenAuthenticator = new AccessTokenAuthenticator(jwtProvider, loginSessionStore);
        this.allianceChatMembership = allianceChatMembership;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> accessor.setUser(new StompUser(authenticate(accessor)));
            case SUBSCRIBE -> authorizeSubscribe(requireUser(accessor), accessor.getDestination());
            case SEND -> authorizeSend(requireUser(accessor), accessor.getDestination());
            default -> {
                // UNSUBSCRIBE, DISCONNECT, ACK 등은 목적지 권한과 상관없다
            }
        }
        return message;
    }

    private AuthUser authenticate(StompHeaderAccessor accessor) {
        String token = AccessTokenAuthenticator.bearerToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED, "STOMP CONNECT에 Bearer 토큰 없음");
        }
        return this.accessTokenAuthenticator.authenticate(token);
    }

    // CONNECT에 실패하면 Spring이 연결을 끊으므로 보통은 여기까지 오지 않는다. CONNECT 없이 보낸 프레임을 막는다
    private static AuthUser requireUser(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (!(user instanceof StompUser stompUser)) {
            throw new BusinessException(ErrorCode.COMMON_UNAUTHORIZED, "인증되지 않은 STOMP 세션: " + accessor.getCommand());
        }
        return stompUser.authUser();
    }

    private void authorizeSubscribe(AuthUser user, @Nullable String destination) {
        if (ChatDestinations.ERROR_QUEUE.equals(destination)) {
            return;
        }
        Matcher university = destination == null ? null : UNIVERSITY_TOPIC.matcher(destination);
        if (university != null && university.matches()) {
            if (Long.parseLong(university.group(1)) != user.universityId()) {
                throw new BusinessException(ErrorCode.CHAT_NOT_MEMBER,
                        "다른 학교 방 구독 userId=" + user.userId() + ", destination=" + destination);
            }
            return;
        }
        Matcher alliance = destination == null ? null : ALLIANCE_TOPIC.matcher(destination);
        if (alliance != null && alliance.matches()) {
            this.allianceChatMembership.requireMember(user, Long.parseLong(alliance.group(1)));
            return;
        }
        throw new BusinessException(ErrorCode.COMMON_FORBIDDEN,
                "허용하지 않는 구독 목적지 userId=" + user.userId() + ", destination=" + destination);
    }

    private static void authorizeSend(AuthUser user, @Nullable String destination) {
        if (destination == null || !destination.startsWith(ChatDestinations.APP_PREFIX)) {
            throw new BusinessException(ErrorCode.COMMON_FORBIDDEN,
                    "허용하지 않는 전송 목적지 userId=" + user.userId() + ", destination=" + destination);
        }
    }
}
