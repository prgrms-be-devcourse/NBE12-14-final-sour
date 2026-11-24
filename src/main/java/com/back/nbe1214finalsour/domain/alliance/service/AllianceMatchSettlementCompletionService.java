package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AllianceMatchSettlementCompletionService {
    private final AllianceRepository allianceRepository;
    private final AllianceMatchRepository allianceMatchRepository;
    private final AllianceMatchSettlementRepository settlementRepository;

    @Transactional
    public void completeApplied(Long matchId) {
        if (matchId == null || matchId <= 0) {
            throw new IllegalArgumentException("대결 ID가 올바르지 않습니다.");
        }
        AllianceMatchPair pair = allianceMatchRepository.findPairById(matchId)
                .orElseThrow(() -> new IllegalStateException("대결을 찾을 수 없습니다."));
        Long firstId = Math.min(pair.allianceAId(), pair.allianceBId());
        Long secondId = Math.max(pair.allianceAId(), pair.allianceBId());
        Alliance first = allianceRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new IllegalStateException("연합을 찾을 수 없습니다."));
        Alliance second = allianceRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new IllegalStateException("연합을 찾을 수 없습니다."));
        AllianceMatch match = allianceMatchRepository.findByIdForUpdate(matchId)
                .orElseThrow(() -> new IllegalStateException("대결을 찾을 수 없습니다."));
        if (!match.getAllianceAId().equals(pair.allianceAId())
                || !match.getAllianceBId().equals(pair.allianceBId())) {
            throw new IllegalStateException("대결 참가 연합이 일치하지 않습니다.");
        }
        // 양쪽 연합/대결 잠금 다음에 정산 행 잠금. 모든 완료 처리에서 순서를 동일하게 한다.
        AllianceMatchSettlement settlement = settlementRepository.findByIdForUpdate(matchId)
                .orElseThrow(() -> new IllegalStateException("정산 이력을 찾을 수 없습니다."));
        if (settlement.getStatus() == AllianceMatchSettlementStatus.COMPLETED) {
            if (match.getStatus() != AllianceMatchStatus.FINISHED
                    || !settlement.getAScore().equals(match.getAScore())
                    || !settlement.getBScore().equals(match.getBScore())) {
                throw new IllegalStateException("정산 완료 상태와 대결 상태가 일치하지 않습니다.");
            }
            return;
        }
        if (settlement.getStatus() != AllianceMatchSettlementStatus.APPLIED) {
            throw new IllegalStateException("Redis 적용이 확인되지 않은 정산입니다.");
        }
        if (match.getStatus() != AllianceMatchStatus.IN_PROGRESS) {
            throw new IllegalStateException("진행 중인 대결만 종료할 수 있습니다.");
        }
        Alliance allianceA = pair.allianceAId().equals(firstId) ? first : second;
        Alliance allianceB = pair.allianceBId().equals(firstId) ? first : second;
        match.finish(OffsetDateTime.now(), settlement.getAScore(), settlement.getBScore());
        allianceA.finishMatch();
        allianceB.finishMatch();
        settlement.markCompleted();
    }
}
