package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse;
import com.back.nbe1214finalsour.domain.auth.dto.LoginResponse.LoginUser;
import com.back.nbe1214finalsour.domain.auth.repository.LoginAttemptStore;
import com.back.nbe1214finalsour.domain.auth.service.RefreshTokenService.IssuedSession;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.LoginSessionStore;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 이메일·비밀번호 로그인 (API 명세서 {@code POST /api/auth/login}).
 *
 * <ol>
 *   <li>잠금 확인과 시도 횟수 증가 ({@link LoginAttemptStore}). 잠겼으면 429</li>
 *   <li>탈퇴하지 않은 회원을 이메일 해시로 찾고 비밀번호 확인. 회원이 없거나 틀리면 401이고, 5번째 실패면 잠그고 429</li>
 *   <li>비밀번호가 맞으면 실패 횟수를 지운 뒤 상태 확인. 정지·차단이면 403</li>
 *   <li>새 로그인 세션 시작 (계정당 1기기, SCRUM-84): 이 회원의 다른 기기 refresh 토큰을 모두 지우고, 새 세션 ID를
 *       refresh 토큰 행·Redis 현재 세션·access 토큰 {@code sid}에 넣는다. 요청에 이전 refresh 토큰 쿠키가 있으면 그 행도 지운다
 *       (SCRUM-72 A)</li>
 * </ol>
 *
 * <p>상태(정지·차단)는 비밀번호가 맞을 때만 알려준다. 틀린 비밀번호로 계정 상태를 알아낼 수 없게 하기 위함이다.
 */
@Service
public class LoginService {

    static final int MAX_FAILURES = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    static final Duration LOCK_DURATION = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final EmailHasher emailHasher;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptStore loginAttemptStore;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final LoginSessionStore loginSessionStore;

    /**
     * 없는 이메일일 때도 비밀번호 확인을 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다.
     * 값은 아무 비밀번호와도 맞지 않는 임의 문자열의 해시다.
     */
    private final String dummyPasswordHash;

    public LoginService(UserRepository userRepository, EmailHasher emailHasher, PasswordEncoder passwordEncoder,
            LoginAttemptStore loginAttemptStore, JwtProvider jwtProvider, RefreshTokenService refreshTokenService,
            LoginSessionStore loginSessionStore) {
        this.userRepository = userRepository;
        this.emailHasher = emailHasher;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptStore = loginAttemptStore;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
        this.loginSessionStore = loginSessionStore;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * @param previousRefreshToken 요청에 실려 온 {@code refreshToken} 쿠키 값 (없으면 {@code null}). 로그인이 성공할 때만 지운다
     * @throws BusinessException 429 {@code AUTH_LOGIN_LOCKED}, 401 {@code AUTH_INVALID_CREDENTIALS},
     *                           403 {@code USER_SUSPENDED}
     */
    public LoginResult login(String email, String password, String previousRefreshToken) {
        String emailHash = emailHasher.hash(email);
        long attempt = loginAttemptStore.tryAcquire(emailHash, MAX_FAILURES, FAILURE_WINDOW);
        if (attempt < 0) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_LOCKED);
        }

        Optional<User> found = userRepository.findByEmailHashAndDeletedAtIsNull(emailHash);
        String encodedPassword = found.map(User::getPassword).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(password, encodedPassword) && found.isPresent();
        if (!matches) {
            if (attempt >= MAX_FAILURES) {
                loginAttemptStore.lock(emailHash, LOCK_DURATION);
                throw new BusinessException(ErrorCode.AUTH_LOGIN_LOCKED, "로그인 " + attempt + "번째 실패로 잠금");
            }
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "로그인 " + attempt + "번째 실패");
        }
        loginAttemptStore.reset(emailHash);

        User user = found.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED,
                    "로그인 거부: userId=" + user.getId() + ", status=" + user.getStatus());
        }

        // DB 커밋 뒤 Redis 현재 세션을 바꾼다. Redis가 실패하면 로그인은 500이고 다시 로그인하면 된다
        IssuedSession session = refreshTokenService.startSession(user.getId(), previousRefreshToken);
        loginSessionStore.put(user.getId(), session.sessionId());

        AuthUser authUser = new AuthUser(user.getId(), user.getUniversityId(), user.getDepartmentId(), user.getRole());
        LoginResponse response = new LoginResponse(jwtProvider.createAccessToken(authUser, session.sessionId()),
                jwtProvider.accessTokenExpiresInSeconds(), LoginUser.from(user));
        return new LoginResult(response, session.refreshToken(), jwtProvider.refreshTokenTtl());
    }

    /**
     * @param refreshToken    refresh 토큰 원문. 쿠키로만 보낸다
     * @param refreshTokenTtl 쿠키 {@code Max-Age}
     */
    public record LoginResult(LoginResponse response, String refreshToken, Duration refreshTokenTtl) {

        /** 토큰이 로그에 남지 않도록 가린다 */
        @Override
        public String toString() {
            return "LoginResult[userId=" + response.user().id() + ", tokens=****]";
        }
    }
}
