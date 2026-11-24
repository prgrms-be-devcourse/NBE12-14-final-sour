package com.back.nbe1214finalsour.domain.chat.websocket;

import com.back.nbe1214finalsour.global.security.AuthUser;
import java.security.Principal;

/**
 * STOMP 세션의 로그인 회원. CONNECT 때 access 토큰으로 만들어 세션에 붙이고, 이후 프레임과 {@code @MessageMapping}에서 꺼내 쓴다.
 *
 * <p>이름은 회원 ID다. {@code /user/queue/errors} 같은 사용자 목적지가 이 이름으로 세션을 찾는다.
 */
public record StompUser(AuthUser authUser) implements Principal {

    @Override
    public String getName() {
        return String.valueOf(this.authUser.userId());
    }
}
