package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlement;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceMatchSettlementStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AllianceMatchSettlementApplicationService {
    private final AllianceMatchSettlementRepository settlementRepository;
    private final AllianceMatchRedisSettlementService redisSettlementService;

    @Transactional
    public void applyPending(Long matchId) {
        if (matchId == null || matchId <= 0) {
            throw new IllegalArgumentException("대결 ID가 올바르지 않습니다.");
        }
        AllianceMatchSettlement s = settlementRepository.findByIdForUpdate(matchId)
                .orElseThrow(() -> new IllegalStateException("정산 이력을 찾을 수 없습니다."));
        if (s.getStatus() != AllianceMatchSettlementStatus.PENDING) return;
        if (s.getAScore().equals(s.getBScore())) {
            // 무승부는 Redis 점수 변경이 전혀 없다.
            s.markApplied();
            return;
        }
        if (s.getAScore() > s.getBScore()) {
            redisSettlementService.settleOnce(matchId,
                    s.getBUniversity1Id(), s.getBUniversity2Id(),
                    s.getAUniversity1Id(), s.getAUniversity2Id(),
                    s.getBUniversity1Contribution(), s.getBUniversity2Contribution());
        } else {
            redisSettlementService.settleOnce(matchId,
                    s.getAUniversity1Id(), s.getAUniversity2Id(),
                    s.getBUniversity1Id(), s.getBUniversity2Id(),
                    s.getAUniversity1Contribution(), s.getAUniversity2Contribution());
        }
        // Lua가 1(신규 적용) 또는 0(기존 적용)을 반환한 경우만 도달한다.
        s.markApplied();
    }
}
