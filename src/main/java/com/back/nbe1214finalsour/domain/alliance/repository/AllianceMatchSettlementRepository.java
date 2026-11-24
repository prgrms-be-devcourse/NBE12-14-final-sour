package com.back.nbe1214finalsour.domain.alliance.repository;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlement;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AllianceMatchSettlementRepository extends JpaRepository<AllianceMatchSettlement, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AllianceMatchSettlement s WHERE s.matchId = :matchId")
    Optional<AllianceMatchSettlement> findByIdForUpdate(@Param("matchId") Long matchId);

    @Query("""
            SELECT s.matchId FROM AllianceMatchSettlement s
            WHERE s.status IN :statuses ORDER BY s.matchId ASC
            """)
    List<Long> findIncompleteIds(@Param("statuses") List<AllianceMatchSettlementStatus> statuses,
                                 Pageable pageable);
}
