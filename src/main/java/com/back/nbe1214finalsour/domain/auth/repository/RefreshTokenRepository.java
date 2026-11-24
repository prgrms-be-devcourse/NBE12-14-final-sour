package com.back.nbe1214finalsour.domain.auth.repository;

import com.back.nbe1214finalsour.domain.auth.entity.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 모든 {@code token} 인자는 원문이 아니라 SHA-256 hex다 ({@code RefreshTokenService.hash}) */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    /** 지운 행 수. 같은 토큰으로 동시에 재발급하면 한쪽만 1을 받는다 (행 잠금 뒤 다시 평가 — PostgreSQL READ COMMITTED) */
    @Modifying
    @Query("delete from RefreshToken r where r.token = :token")
    int deleteByToken(@Param("token") String token);

    @Modifying
    @Query("delete from RefreshToken r where r.token = :token and r.userId = :userId")
    int deleteByTokenAndUserId(@Param("token") String token, @Param("userId") Long userId);

    /** 이 회원의 만료된 행을 지운다 ({@code idx_refresh_token_user} 사용) */
    @Modifying
    @Query("delete from RefreshToken r where r.userId = :userId and r.expiredAt <= :now")
    int deleteExpiredByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /** 이 회원의 행을 모두 지운다 ({@code idx_refresh_token_user} 사용) */
    @Modifying
    @Query("delete from RefreshToken r where r.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
