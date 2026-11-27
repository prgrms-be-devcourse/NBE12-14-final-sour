package com.back.nbe1214finalsour.domain.user.devaccount;

import com.back.nbe1214finalsour.domain.university.entity.Department;
import com.back.nbe1214finalsour.domain.university.entity.University;
import com.back.nbe1214finalsour.domain.university.repository.DepartmentRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.domain.user.validation.Nickname;
import com.back.nbe1214finalsour.domain.user.validation.Password;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.crypto.EmailHasher;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 앱이 시작될 때 개발자 공용 계정이 없으면 만든다 (SCRUM-237, GitHub #142). 로컬 Postgres와 운영 RDB에서 같게 동작한다.
 *
 * <p>SQL 시드로 만들지 않는 이유: {@code email}(AES-GCM)과 {@code email_hash}(HMAC)가 환경별 키로 만들어지고,
 * 비밀번호도 환경마다 다르다. 소속 학교(와플대학교)는 학교 캐시보다 먼저 있어야 해서 V14 마이그레이션이 넣는다.
 *
 * <ul>
 *   <li>꺼져 있거나 이메일·비밀번호가 없으면 만들지 않는다. 배포를 막지 않는다.</li>
 *   <li>같은 이메일의 계정이 있으면 아무것도 바꾸지 않는다 (비밀번호·닉네임·권한 모두 그대로).</li>
 *   <li>학교·학과가 없거나 비밀번호가 규칙에 맞지 않으면 설정 오류라 시작에 실패한다.</li>
 *   <li>서버 여러 대가 동시에 만들면 {@code uk_users_email_hash}가 하나만 남긴다. 진 쪽은 이미 만든 것으로 본다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(DevAccountProperties.class)
public class DevAccountInitializer implements ApplicationRunner {

    private final DevAccountProperties properties;
    private final UserRepository userRepository;
    private final UniversityRepository universityRepository;
    private final DepartmentRepository departmentRepository;
    private final EmailHasher emailHasher;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        createIfAbsent();
        createDemoAccountsIfAbsent();
    }

    /** @return 이번에 만들었으면 true */
    public boolean createIfAbsent() {
        if (!properties.enabled()) {
            log.info("[DevAccount] dev-account.enabled=false라 개발자 계정을 만들지 않습니다");
            return false;
        }
        if (!properties.hasEmail()) {
            log.warn("[DevAccount] DEV_ACCOUNT_EMAIL이 없어 개발자 계정을 만들지 않습니다");
            return false;
        }
        if (!properties.hasPassword()) {
            log.warn("[DevAccount] DEV_ACCOUNT_PASSWORD가 없어 개발자 계정을 만들지 않습니다");
            return false;
        }
        if (!validator.validate(new PasswordRule(properties.password())).isEmpty()) {
            throw new IllegalStateException(
                    "DEV_ACCOUNT_PASSWORD가 비밀번호 규칙(8~64자, 공백 없는 ASCII, 영문·숫자·특수문자 포함)에 맞지 않습니다");
        }

        String email = EmailHasher.normalize(properties.email());
        String emailHash = emailHasher.hash(email);
        if (userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
            log.info("[DevAccount] 개발자 계정이 이미 있어 그대로 둡니다");
            return false;
        }

        University university = universityRepository.findByName(properties.universityName())
                .orElseThrow(() -> new IllegalStateException(
                        "dev-account.university-name 학교가 DB에 없습니다: " + properties.universityName()));
        Department department = departmentRepository
                .findByUniversity_IdAndName(university.getId(), properties.departmentName())
                .orElseThrow(() -> new IllegalStateException("dev-account.department-name 학과가 "
                        + properties.universityName() + "에 없습니다: " + properties.departmentName()));
        if (userRepository.existsActiveNicknameIgnoreCase(properties.nickname())) {
            log.warn("[DevAccount] 닉네임 {}을(를) 다른 회원이 써서 개발자 계정을 만들지 않습니다 (DEV_ACCOUNT_NICKNAME으로 바꾼다)",
                    properties.nickname());
            return false;
        }

        try {
            User user = userRepository.saveAndFlush(User.create(university.getId(), department.getId(),
                    properties.nickname(), email, emailHash, passwordEncoder.encode(properties.password()),
                    OffsetDateTime.now(clock), properties.role()));
            log.info("[DevAccount] 개발자 계정을 만들었습니다: userId={}, role={}, university={}",
                    user.getId(), properties.role(), university.getName());
            return true;
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
                log.info("[DevAccount] 다른 서버가 먼저 개발자 계정을 만들었습니다");
                return false;
            }
            throw e;
        }
    }

    /**
     * 시연용 계정 {@code dev-account.demo-count}개를 없는 것만 만든다. 개발자 계정과 같은 비밀번호·학교·학과를 쓰고,
     * 이메일은 개발자 이메일의 @ 앞에 번호를 붙인다(dev-account@example.com → dev-account1@example.com ~ dev-account100@example.com).
     * 닉네임은 개발자 닉네임 뒤에 번호를 붙인다. 권한은 개발자 계정 설정과 관계없이 USER다.
     *
     * <ul>
     *   <li>꺼져 있거나 개수가 0이거나 이메일·비밀번호가 없으면 만들지 않는다.</li>
     *   <li>같은 이메일이 이미 있으면 그 번호는 건너뛴다. 기존 계정은 바꾸지 않는다.</li>
     *   <li>닉네임이 규칙에 맞지 않거나 다른 회원이 쓰고 있으면 그 번호만 건너뛴다.</li>
     *   <li>비밀번호가 모두 같아 해시는 한 번만 만든다.</li>
     * </ul>
     *
     * @return 이번에 만든 계정 수
     */
    public int createDemoAccountsIfAbsent() {
        int count = properties.demoCount();
        if (!properties.enabled() || count <= 0 || !properties.hasEmail() || !properties.hasPassword()) {
            return 0;
        }
        if (!validator.validate(new PasswordRule(properties.password())).isEmpty()) {
            throw new IllegalStateException(
                    "DEV_ACCOUNT_PASSWORD가 비밀번호 규칙(8~64자, 공백 없는 ASCII, 영문·숫자·특수문자 포함)에 맞지 않습니다");
        }
        String baseEmail = EmailHasher.normalize(properties.email());
        int at = baseEmail.indexOf('@');
        String localPart = baseEmail.substring(0, at);
        String domain = baseEmail.substring(at);

        University university = universityRepository.findByName(properties.universityName())
                .orElseThrow(() -> new IllegalStateException(
                        "dev-account.university-name 학교가 DB에 없습니다: " + properties.universityName()));
        Department department = departmentRepository
                .findByUniversity_IdAndName(university.getId(), properties.departmentName())
                .orElseThrow(() -> new IllegalStateException("dev-account.department-name 학과가 "
                        + properties.universityName() + "에 없습니다: " + properties.departmentName()));

        String encodedPassword = null;
        int created = 0;
        for (int i = 1; i <= count; i++) {
            String email = localPart + i + domain;
            String emailHash = emailHasher.hash(email);
            if (userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
                continue;
            }
            String nickname = properties.nickname() + i;
            if (!validator.validate(new NicknameRule(nickname)).isEmpty()
                    || userRepository.existsActiveNicknameIgnoreCase(nickname)) {
                log.warn("[DevAccount] 시연용 계정 {}번은 닉네임 {}을(를) 쓸 수 없어 만들지 않습니다", i, nickname);
                continue;
            }
            if (encodedPassword == null) {
                encodedPassword = passwordEncoder.encode(properties.password());
            }
            try {
                userRepository.saveAndFlush(User.create(university.getId(), department.getId(), nickname, email,
                        emailHash, encodedPassword, OffsetDateTime.now(clock), Role.USER));
                created++;
            } catch (DataIntegrityViolationException e) {
                if (!userRepository.existsByEmailHashAndDeletedAtIsNull(emailHash)) {
                    throw e;
                }
                // 다른 서버가 같은 번호를 먼저 만들었다
            }
        }
        if (created > 0) {
            log.info("[DevAccount] 시연용 계정 {}개를 만들었습니다 (전체 {}개, university={})", created, count, university.getName());
        }
        return created;
    }

    /** 회원가입과 같은 닉네임 규칙({@link Nickname})으로 검사한다 */
    private record NicknameRule(@Nickname String nickname) {
    }

    /** 회원가입과 같은 비밀번호 규칙({@link Password})으로 검사한다 */
    private record PasswordRule(@Password String password) {
    }
}
