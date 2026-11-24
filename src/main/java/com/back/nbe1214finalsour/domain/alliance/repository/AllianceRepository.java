package com.back.nbe1214finalsour.domain.alliance.repository;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;

public interface AllianceRepository extends JpaRepository<Alliance, Long> {

    @Query(value = """
        SELECT id FROM alliance WHERE status <> 'EXPIRED'
          AND deleted_at IS NULL AND expired_at <= :now
        ORDER BY id LIMIT 100
        """, nativeQuery = true)
    List<Long> findDueExpirationIds(@Param("now") OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Alliance a WHERE a.id = :allianceId")
    Optional<Alliance> findByIdForUpdate(
            @Param("allianceId") Long allianceId
    );

    /**
     * 학교가 지금 소속된 연합. 승인된 소속 행은 학교마다 하나뿐이다({@code uk_alliance_university_one_accepted}).
     * 만료 스케줄러가 돌기 전이라도 {@code expired_at}이 지났으면 소속이 아니다.
     */
    @Query("""
        SELECT a FROM Alliance a, AllianceUniversity au
        WHERE au.allianceId = a.id
          AND au.universityId = :universityId
          AND au.status = :accepted
          AND au.deletedAt IS NULL
          AND a.deletedAt IS NULL
          AND a.status <> :expired
          AND a.expiredAt > :now
        """)
    Optional<Alliance> findCurrentByUniversityId(
            @Param("universityId") Long universityId,
            @Param("now") OffsetDateTime now,
            @Param("accepted") AllianceUniversityStatus accepted,
            @Param("expired") AllianceStatus expired
    );

    default Optional<Alliance> findCurrentByUniversityId(Long universityId, OffsetDateTime now) {
        return findCurrentByUniversityId(universityId, now, AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED);
    }
}
