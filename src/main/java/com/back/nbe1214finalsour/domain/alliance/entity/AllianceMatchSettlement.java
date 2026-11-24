package com.back.nbe1214finalsour.domain.alliance.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;

@Entity
@Table(name = "alliance_match_settlement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllianceMatchSettlement extends BaseTimeEntity {
    @Id
    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "a_score", nullable = false)
    private Long aScore;
    @Column(name = "b_score", nullable = false)
    private Long bScore;

    @Column(name = "a_university_1_id", nullable = false)
    private Long aUniversity1Id;
    @Column(name = "a_university_2_id", nullable = false)
    private Long aUniversity2Id;
    @Column(name = "b_university_1_id", nullable = false)
    private Long bUniversity1Id;
    @Column(name = "b_university_2_id", nullable = false)
    private Long bUniversity2Id;

    @Column(name = "a_university_1_contribution", nullable = false)
    private Long aUniversity1Contribution;
    @Column(name = "a_university_2_contribution", nullable = false)
    private Long aUniversity2Contribution;
    @Column(name = "b_university_1_contribution", nullable = false)
    private Long bUniversity1Contribution;
    @Column(name = "b_university_2_contribution", nullable = false)
    private Long bUniversity2Contribution;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllianceMatchSettlementStatus status;

    public static AllianceMatchSettlement prepare(
            Long matchId, long aScore, long bScore,
            Long aUniversity1Id, Long aUniversity2Id,
            Long bUniversity1Id, Long bUniversity2Id,
            long aUniversity1Contribution, long aUniversity2Contribution,
            long bUniversity1Contribution, long bUniversity2Contribution
    ) {
        if (matchId == null || matchId <= 0) {
            throw new IllegalArgumentException("대결 ID가 올바르지 않습니다.");
        }
        if (aScore < 0 || bScore < 0 || aUniversity1Contribution < 0
                || aUniversity2Contribution < 0 || bUniversity1Contribution < 0
                || bUniversity2Contribution < 0) {
            throw new IllegalArgumentException("정산 점수는 음수일 수 없습니다.");
        }
        if (aUniversity1Id == null || aUniversity2Id == null
                || bUniversity1Id == null || bUniversity2Id == null) {
            throw new IllegalArgumentException("정산 대상 학교 ID는 필수입니다.");
        }
        List<Long> ids = List.of(aUniversity1Id, aUniversity2Id,
                bUniversity1Id, bUniversity2Id);
        if (ids.stream().anyMatch(id -> id <= 0) || new HashSet<>(ids).size() != 4) {
            throw new IllegalArgumentException("정산 대상 학교 ID가 올바르지 않습니다.");
        }
        AllianceMatchSettlement s = new AllianceMatchSettlement();
        s.matchId = matchId;
        s.aScore = aScore;
        s.bScore = bScore;
        s.aUniversity1Id = aUniversity1Id;
        s.aUniversity2Id = aUniversity2Id;
        s.bUniversity1Id = bUniversity1Id;
        s.bUniversity2Id = bUniversity2Id;
        s.aUniversity1Contribution = aUniversity1Contribution;
        s.aUniversity2Contribution = aUniversity2Contribution;
        s.bUniversity1Contribution = bUniversity1Contribution;
        s.bUniversity2Contribution = bUniversity2Contribution;
        s.status = AllianceMatchSettlementStatus.PENDING;
        return s;
    }

    public void markApplied() {
        if (status != AllianceMatchSettlementStatus.PENDING) {
            throw new IllegalStateException("대기 중인 정산만 적용할 수 있습니다.");
        }
        status = AllianceMatchSettlementStatus.APPLIED;
    }

    public void markCompleted() {
        if (status != AllianceMatchSettlementStatus.APPLIED) {
            throw new IllegalStateException("적용된 정산만 완료할 수 있습니다.");
        }
        status = AllianceMatchSettlementStatus.COMPLETED;
    }
}
