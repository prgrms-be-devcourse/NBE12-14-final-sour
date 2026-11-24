package com.back.nbe1214finalsour.domain.auth.service;

import com.back.nbe1214finalsour.domain.auth.dto.DepartmentSummary;
import com.back.nbe1214finalsour.domain.auth.dto.SignupRequest;
import com.back.nbe1214finalsour.domain.auth.dto.SignupResponse;
import com.back.nbe1214finalsour.domain.auth.dto.UniversitySummary;
import com.back.nbe1214finalsour.domain.auth.repository.EmailVerificationStore;
import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 회원가입 (API 명세서 {@code POST /api/auth/signup}).
 *
 * <p>가입 토큰의 이메일로 학교를 정하고, 학과가 그 학교 소속인지 확인한 뒤 회원을 저장한다.
 * 가입 토큰은 회원 저장이 커밋된 뒤에 지운다. 닉네임 중복 같은 실패에는 토큰이 남아 이메일 인증을 다시 하지 않아도 된다.
 *
 * <p>앞의 중복 검사를 동시에 통과한 요청은 DB 유니크 인덱스에서 막히고, {@code GlobalExceptionHandler}가
 * 제약 이름으로 409 코드를 정한다 ({@code uk_users_email_hash} → {@code AUTH_EMAIL_ALREADY_REGISTERED},
 * {@code uk_users_nickname_ci} → {@code USER_NICKNAME_DUPLICATED}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    private final EmailVerificationStore store;
    private final EmailUniversityResolver universityResolver;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final EmailHasher emailHasher;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = store.findSignupEmail(request.signupToken())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_SIGNUP_TOKEN));
        University university = universityResolver.resolve(email);
        Department department = departmentRepository.findById(request.departmentId())
                .filter(found -> found.getUniversity().getId().equals(university.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_DEPARTMENT_MISMATCH,
                        "departmentId=" + request.departmentId() + ", universityId=" + university.getId()));

        String emailHash = emailHasher.hash(email);
        if (userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_REGISTERED);
        }
        if (userRepository.existsActiveNicknameIgnoreCase(request.nickname())) {
            throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
        }

        User user = userRepository.saveAndFlush(User.create(university.getId(), department.getId(),
                request.nickname(), email, emailHash, passwordEncoder.encode(request.password()),
                OffsetDateTime.now(clock)));
        deleteSignupTokenAfterCommit(request.signupToken());

        return new SignupResponse(user.getId(), user.getNickname(),
                UniversitySummary.from(university), DepartmentSummary.from(department));
    }

    private void deleteSignupTokenAfterCommit(String signupToken) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    store.deleteSignupToken(signupToken);
                } catch (RuntimeException e) {
                    // 회원은 이미 저장됐다. 남은 토큰은 30분 뒤 만료되고, 다시 쓰면 이메일 중복(409)으로 막힌다
                    log.warn("가입 토큰을 지우지 못했습니다", e);
                }
            }
        });
    }
}
