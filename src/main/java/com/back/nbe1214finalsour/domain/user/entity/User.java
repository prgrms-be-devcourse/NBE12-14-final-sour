package com.back.nbe1214finalsour.domain.user.entity;

import com.back.nbe1214finalsour.global.crypto.EncryptedEmailConverter;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import com.back.nbe1214finalsour.global.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

/**
 * 회원 ({@code users}).
 *
 * <p>학교·학과는 아직 엔티티 연관 대신 ID로 둔다 (학교·학과 엔티티는 3번 담당 작업). 학과가 그 학교 소속인지는
 * DB 복합 외래키 {@code fk_users_department}가 확인한다.
 *
 * <p>클리커 레벨과 점수는 다른 모듈이 JDBC 조건부 UPDATE로 바꾸므로 JPA는 쓰지 않는다. 가입할 때는 DB 기본값
 * (레벨 1, 점수 0)이 들어가고, 저장 직후 Hibernate가 다시 읽어 채운다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "university_id", nullable = false)
    private Long universityId;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    /** 클리커 레벨 (= clicker.id). 업그레이드는 {@code JdbcUserClickerLevelStore}가 바꾼다 */
    @Generated
    @Column(name = "clicker_id", nullable = false, updatable = false)
    private Long clickerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false, length = 30)
    private String nickname;

    /** 정규화한 학교 이메일 원문. DB에는 암호문으로 저장된다 */
    @Convert(converter = EncryptedEmailConverter.class)
    @Column(nullable = false, length = 512)
    private String email;

    /** 이메일 조회용 해시 ({@code EmailHasher}). 탈퇴하지 않은 회원끼리 유일하다 ({@code uk_users_email_hash}) */
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    /** BCrypt로 인코딩한 비밀번호 */
    @Column(nullable = false, length = 100)
    private String password;

    /** 학교 이메일 인증을 마친 시각 */
    @Column(name = "approved_at", nullable = false)
    private OffsetDateTime approvedAt;

    /**
     * 기여도 (순위·재화 겸용). 실시간 값은 Redis {@code RANK_USER} ZSet이고 ({@code RedisContributionWallet}),
     * 이 열은 클릭 배치가 반영한 값이다. 응답에는 {@code UserScoreCache}로 Redis 값을 먼저 쓴다
     */
    @Generated
    @Column(name = "total_contrib_score", nullable = false, updatable = false)
    private Long totalContribScore;

    @Generated
    @Column(name = "total_attack_score", nullable = false, updatable = false)
    private Long totalAttackScore;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    /** 탈퇴 시각. 값이 있으면 {@code status}는 {@code WITHDRAWN}이다 ({@code ck_users_withdrawn}) */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * 이메일 인증을 마친 새 회원을 만든다. 권한은 USER, 상태는 ACTIVE다.
     *
     * @param email           정규화한 이메일 ({@code EmailHasher.normalize})
     * @param emailHash       같은 이메일의 {@code EmailHasher.hash} 값
     * @param encodedPassword 인코딩한 비밀번호 (원문을 넣지 않는다)
     */
    public static User create(Long universityId, Long departmentId, String nickname, String email, String emailHash,
            String encodedPassword, OffsetDateTime approvedAt) {
        return create(universityId, departmentId, nickname, email, emailHash, encodedPassword, approvedAt, Role.USER);
    }

    /**
     * 권한을 정해 회원을 만든다. 가입 API를 거치지 않는 개발자 공용 계정(SCRUM-237)만 쓴다. 상태는 ACTIVE다.
     * 나머지 인자는 {@link #create(Long, Long, String, String, String, String, OffsetDateTime)}와 같다.
     */
    public static User create(Long universityId, Long departmentId, String nickname, String email, String emailHash,
            String encodedPassword, OffsetDateTime approvedAt, Role role) {
        User user = new User();
        user.universityId = universityId;
        user.departmentId = departmentId;
        user.nickname = nickname;
        user.email = email;
        user.emailHash = emailHash;
        user.password = encodedPassword;
        user.approvedAt = approvedAt;
        user.role = role;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    /** @param encodedPassword 인코딩한 비밀번호 (원문을 넣지 않는다) */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** 중복 검사는 호출한 쪽이 한다. 동시 변경은 {@code uk_users_nickname_ci}가 막는다 */
    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 개인 로고 변경권 구매({@code CreditPurchaseService})에서만 부른다. 프로필 수정 API로는 바꾸지 않는다 (SCRUM-105).
     *
     * @param profileImageUrl 검사를 마친 우리 CDN 주소 ({@code ProfileImageUrlPolicy})
     */
    public void changeProfileImageUrl(String profileImageUrl) {
        this.logoUrl = profileImageUrl;
    }

    /**
     * 관리자 정지·차단·해제 (추가 명세 §3-9). 탈퇴는 {@code deleted_at}이 함께 필요하므로 {@link #withdraw}로만 한다.
     *
     * @throws BusinessException 400 {@code COMMON_INVALID_INPUT}: {@code WITHDRAWN}으로 바꾸려 함.
     *                           404 {@code USER_NOT_FOUND}: 이미 탈퇴한 회원
     */
    public void changeStatus(UserStatus status) {
        if (status == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.COMMON_INVALID_INPUT, "탈퇴는 withdraw로만 한다: userId=" + id);
        }
        if (this.deletedAt != null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "탈퇴한 회원의 상태 변경: userId=" + id);
        }
        this.status = status;
    }

    /**
     * 탈퇴 (soft delete). 상태와 탈퇴 시각을 함께 바꾼다 ({@code ck_users_withdrawn}). 탈퇴하면 부분 UK
     * ({@code uk_users_email_hash}, {@code uk_users_nickname_ci})에서 빠져 같은 이메일·닉네임으로 다시 가입할 수 있다.
     */
    public void withdraw(OffsetDateTime withdrawnAt) {
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = withdrawnAt;
    }
}
