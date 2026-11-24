package com.back.nbe1214finalsour.domain.user.repository;

import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.back.nbe1214finalsour.domain.user.repository.projections.UserUniv;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** 채팅 수신 직전 최신 회원/학교 소속을 확인한다. 개인정보 엔티티나 TTL 상태 캐시는 읽지 않는다. */
    boolean existsByIdAndUniversityIdAndStatusAndDeletedAtIsNull(Long id, Long universityId,
            UserStatus status);

    /** 탈퇴하지 않은 회원 중에 이 이메일 해시가 있는지. {@code uk_users_email_hash}와 같은 조건이다 */
    boolean existsByEmailHashAndDeletedAtIsNull(String emailHash);

    /** 탈퇴하지 않은 회원 중 이 이메일 해시를 가진 회원 ({@code uk_users_email_hash}로 많아야 하나) */
    Optional<User> findByEmailHashAndDeletedAtIsNull(String emailHash);

    /**
     * 탈퇴하지 않은 회원 중에 대소문자만 다른 닉네임까지 같은 닉네임이 있는지.
     * {@code uk_users_nickname_ci}(lower(nickname))와 같은 기준이 되도록 {@code lower}로 비교한다
     * (Spring Data의 {@code IgnoreCase}는 {@code upper}를 쓴다)
     */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname) and u.deletedAt is null")
    boolean existsActiveNicknameIgnoreCase(@Param("nickname") String nickname);

    /** {@link #existsActiveNicknameIgnoreCase}에서 이 회원은 빼고 본다 (프로필 수정: 자기 닉네임의 대소문자만 바꾸는 경우) */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname) and u.deletedAt is null"
            + " and u.id <> :userId")
    boolean existsActiveNicknameIgnoreCaseExcept(@Param("nickname") String nickname, @Param("userId") Long userId);

    @Query("select u.id, u.universityId from User u where u.id in :ids")
    List<UserUniv> findUniversityIdsByIdIn(@Param("ids") Collection<Long> ids);
}
