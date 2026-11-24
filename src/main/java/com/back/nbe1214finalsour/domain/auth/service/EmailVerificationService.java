package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.dto.EmailCodeResponse;
import com.back.nbe1214finalsour.domain.auth.dto.EmailVerifyResponse;
import com.back.nbe1214finalsour.domain.auth.dto.UniversitySummary;
import com.back.nbe1214finalsour.domain.auth.mail.VerificationMailSender;
import com.back.nbe1214finalsour.domain.auth.repository.EmailVerificationStore;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.time.ClockConfig;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학교 이메일 인증 (API 명세서 {@code POST /api/auth/email/code}, {@code /email/verify}).
 *
 * <p>코드 요청: 도메인으로 학교 결정 → IP 한도 (SCRUM-100) → 가입된 이메일 확인 → 60초 쿨다운 → 하루 10번 → 코드 저장 → 메일 발송.
 * IP 한도를 가입 확인보다 먼저 두어 가입 여부 대량 조회도 막는다 (보안 점검 #6). 학교 도메인이 아닌 요청은 Redis를 건드리지 않는다.
 * 코드 확인: 맞으면 가입 토큰을 발급하고, 5번 틀리면 코드를 폐기한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailVerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final int DAILY_LIMIT = 10;
    static final int MAX_ATTEMPTS = 5;
    static final Duration SIGNUP_TOKEN_TTL = Duration.ofMinutes(30);

    private static final int CODE_BOUND = 1_000_000;

    private final EmailUniversityResolver universityResolver;
    private final UserRepository userRepository;
    private final EmailVerificationStore store;
    private final VerificationMailSender mailSender;
    private final EmailHasher emailHasher;
    private final AuthIpRateLimiter ipRateLimiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param clientIp 요청한 사용자 IP ({@code request.getRemoteAddr()})
     * @throws BusinessException 400 {@code AUTH_UNSUPPORTED_DOMAIN}, 429 {@code AUTH_TOO_MANY_REQUESTS}·{@code AUTH_CODE_COOLDOWN},
     *                           409 {@code AUTH_EMAIL_ALREADY_REGISTERED}
     */
    public EmailCodeResponse requestCode(String rawEmail, String clientIp) {
        String email = EmailHasher.normalize(rawEmail);
        University university = universityResolver.resolve(email);
        ipRateLimiter.checkEmailCode(clientIp);
        String emailHash = emailHasher.hash(email);

        if (userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED);
        }
        if (!store.tryStartCooldown(emailHash, COOLDOWN)) {
            throw new BusinessException(ErrorCode.AUTH_CODE_COOLDOWN, "60초 쿨다운 중");
        }
        // 명세서에 하루 횟수 초과 전용 코드가 없어 쿨다운 코드를 함께 쓴다
        if (store.incrementDailyCount(emailHash, today()) > DAILY_LIMIT) {
            throw new BusinessException(ErrorCode.AUTH_CODE_COOLDOWN, "하루 " + DAILY_LIMIT + "번 초과");
        }

        String code = newCode();
        store.saveCode(emailHash, code, CODE_TTL);
        try {
            mailSender.sendCode(email, code);
        } catch (RuntimeException e) {
            // 받지 못한 코드가 남아 있거나 쿨다운 때문에 바로 다시 요청하지 못하는 일이 없도록 되돌린다
            store.discardCode(emailHash);
            throw e;
        }
        return new EmailCodeResponse(UniversitySummary.from(university), CODE_TTL.toSeconds());
    }

    public EmailVerifyResponse verifyCode(String rawEmail, String code) {
        String email = EmailHasher.normalize(rawEmail);
        String emailHash = emailHasher.hash(email);

        switch (store.verifyCode(emailHash, code, MAX_ATTEMPTS)) {
            case EXPIRED -> throw new BusinessException(ErrorCode.AUTH_CODE_EXPIRED);
            case MISMATCHED -> throw new BusinessException(ErrorCode.AUTH_INVALID_CODE);
            case ATTEMPTS_EXCEEDED -> throw new BusinessException(ErrorCode.AUTH_CODE_ATTEMPTS_EXCEEDED);
            case MATCHED -> {
                // 일치하면 코드는 이미 지워졌다. 같은 코드로 동시에 확인해도 한 요청만 토큰을 받는다
            }
        }

        University university = universityResolver.resolve(email);
        String signupToken = UUID.randomUUID().toString();
        store.saveSignupToken(signupToken, email, SIGNUP_TOKEN_TTL);
        return new EmailVerifyResponse(signupToken, UniversitySummary.from(university), SIGNUP_TOKEN_TTL.toSeconds());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ClockConfig.KST);
    }

    private String newCode() {
        return "%06d".formatted(random.nextInt(CODE_BOUND));
    }
}
