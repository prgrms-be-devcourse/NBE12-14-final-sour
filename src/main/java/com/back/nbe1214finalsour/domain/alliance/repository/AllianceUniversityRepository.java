package com.back.nbe1214finalsour.domain.alliance.repository;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AllianceUniversityRepository
        extends JpaRepository<AllianceUniversity, Long> {

    List<AllianceUniversity> findByAllianceIdAndDeletedAtIsNullOrderByIdAsc(Long allianceId);

    boolean existsByUniversityIdAndStatusAndDeletedAtIsNull(
            Long universityId,
            AllianceUniversityStatus status
    );

    List<AllianceUniversity> findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
            Long allianceId,
            AllianceUniversityStatus status
    );

    long countByAllianceIdAndStatusAndDeletedAtIsNull(
            Long allianceId,
            AllianceUniversityStatus status
    );

    @Query("""
        SELECT au.allianceId
        FROM AllianceUniversity au
        WHERE au.id = :applicationId
          AND au.deletedAt IS NULL
        """)
    Optional<Long> findAllianceIdByApplicationId(
            @Param("applicationId") Long applicationId
    );

    boolean existsByAllianceIdAndUniversityIdAndStatusAndDeletedAtIsNull(
            Long allianceId,
            Long universityId,
            AllianceUniversityStatus status
    );

    /**
     * 두 학교가 지금 같은 연합에 속하는지 (SCRUM-187 연합 지원). 둘 다 승인된 소속이고, 연합이 삭제·만료되지 않았으며
     * 만료 시각이 지나지 않아야 한다. 만료 스케줄러가 돌기 전이라도 {@code expired_at}이 지났으면 동맹이 아니다
     */
    @Query("""
        SELECT COUNT(a) > 0
        FROM Alliance a, AllianceUniversity mine, AllianceUniversity other
        WHERE mine.allianceId = a.id
          AND other.allianceId = a.id
          AND mine.universityId = :universityId
          AND other.universityId = :otherUniversityId
          AND mine.status = :accepted
          AND other.status = :accepted
          AND mine.deletedAt IS NULL
          AND other.deletedAt IS NULL
          AND a.deletedAt IS NULL
          AND a.status <> :expired
          AND a.expiredAt > :now
        """)
    boolean existsCurrentAllyPair(
            @Param("universityId") Long universityId,
            @Param("otherUniversityId") Long otherUniversityId,
            @Param("now") OffsetDateTime now,
            @Param("accepted") AllianceUniversityStatus accepted,
            @Param("expired") AllianceStatus expired
    );
}