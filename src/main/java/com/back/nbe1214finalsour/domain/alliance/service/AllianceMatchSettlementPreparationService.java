package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AllianceMatchSettlementPreparationService {
    private final AllianceRepository allianceRepository;
    private final AllianceMatchRepository matchRepository;
    private final AllianceUniversityRepository allianceUniversityRepository;
    private final AllianceMatchSettlementRepository settlementRepository;

    @Transactional
    public AllianceMatchSettlement prepare(Long matchId, AllianceMatchSettlementSnapshot snapshot) {
        if (matchId == null || matchId <= 0 || snapshot == null) {
            throw new IllegalArgumentException("대결 ID와 최종 점수 스냅샷이 필요합니다.");
        }
        // 다른 수락·취소·종료 로직과 동일한 잠금 순서: 연합 ID 오름차순 -> 대결.
        AllianceMatchPair pair = matchRepository.findPairById(matchId)
                .orElseThrow(() -> new IllegalStateException("대결을 찾을 수 없습니다."));
        Long firstId = Math.min(pair.allianceAId(), pair.allianceBId());
        Long secondId = Math.max(pair.allianceAId(), pair.allianceBId());
        Alliance first = allianceRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new IllegalStateException("연합을 찾을 수 없습니다."));
        Alliance second = allianceRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new IllegalStateException("연합을 찾을 수 없습니다."));
        AllianceMatch match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(() -> new IllegalStateException("대결을 찾을 수 없습니다."));
        if (!match.getAllianceAId().equals(pair.allianceAId())
                || !match.getAllianceBId().equals(pair.allianceBId())) {
            throw new IllegalStateException("대결 참가 연합이 일치하지 않습니다.");
        }
        // 이미 저장된 스냅샷은 재시도 시 수정할 수 없다.
        var existing = settlementRepository.findById(matchId);
        if (existing.isPresent()) {
            verifySameSnapshot(existing.get(), snapshot);
            return existing.get();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (match.getStatus() != AllianceMatchStatus.IN_PROGRESS || match.getDeletedAt() != null
                || match.getEndedAt() == null || match.getEndedAt().isAfter(now)) {
            throw new IllegalStateException("종료 시각이 지난 진행 중 대결만 준비할 수 있습니다.");
        }
        if (first.getStatus() != AllianceStatus.IN_MATCH
                || second.getStatus() != AllianceStatus.IN_MATCH) {
            throw new IllegalStateException("양쪽 연합이 대결 중이어야 합니다.");
        }
        AllianceMatchSettlement settlement = AllianceMatchSettlement.prepare(
                matchId, snapshot.aScore(), snapshot.bScore(),
                snapshot.aUniversity1Id(), snapshot.aUniversity2Id(),
                snapshot.bUniversity1Id(), snapshot.bUniversity2Id(),
                snapshot.aUniversity1Contribution(), snapshot.aUniversity2Contribution(),
                snapshot.bUniversity1Contribution(), snapshot.bUniversity2Contribution());
        // 학교 목록을 단순히 클라이언트/외부 입력으로 신뢰하지 않는다.
        verifyMembers(pair.allianceAId(), Set.of(snapshot.aUniversity1Id(), snapshot.aUniversity2Id()));
        verifyMembers(pair.allianceBId(), Set.of(snapshot.bUniversity1Id(), snapshot.bUniversity2Id()));
        return settlementRepository.saveAndFlush(settlement);
    }

    private void verifyMembers(Long allianceId, Set<Long> expected) {
        List<AllianceUniversity> members = allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED);
        if (members.size() != 2 || !Set.copyOf(members.stream()
                .map(AllianceUniversity::getUniversityId).toList()).equals(expected)) {
            throw new IllegalStateException("정산 스냅샷의 참가 학교가 대결 연합과 일치하지 않습니다.");
        }
    }

    private static void verifySameSnapshot(AllianceMatchSettlement s, AllianceMatchSettlementSnapshot v) {
        // 순서까지 포함해 동일한 동결 스냅샷만 재허용한다.
        if (!s.getAScore().equals(v.aScore()) || !s.getBScore().equals(v.bScore())
                || !s.getAUniversity1Id().equals(v.aUniversity1Id())
                || !s.getAUniversity2Id().equals(v.aUniversity2Id())
                || !s.getBUniversity1Id().equals(v.bUniversity1Id())
                || !s.getBUniversity2Id().equals(v.bUniversity2Id())
                || !s.getAUniversity1Contribution().equals(v.aUniversity1Contribution())
                || !s.getAUniversity2Contribution().equals(v.aUniversity2Contribution())
                || !s.getBUniversity1Contribution().equals(v.bUniversity1Contribution())
                || !s.getBUniversity2Contribution().equals(v.bUniversity2Contribution())) {
            throw new IllegalStateException("이미 준비된 대결 정산 스냅샷과 값이 다릅니다.");
        }
    }
}
