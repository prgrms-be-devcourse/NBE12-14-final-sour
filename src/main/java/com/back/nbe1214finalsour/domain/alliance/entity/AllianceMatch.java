package com.back.nbe1214finalsour.domain.alliance.entity;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alliance_match")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllianceMatch extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alliance_a_id", nullable = false)
    private Long allianceAId;

    @Column(name = "alliance_b_id", nullable = false)
    private Long allianceBId;

    @Column(name = "a_university_1_id")
    private Long aUniversity1Id;

    @Column(name = "a_university_2_id")
    private Long aUniversity2Id;

    @Column(name = "b_university_1_id")
    private Long bUniversity1Id;

    @Column(name = "b_university_2_id")
    private Long bUniversity2Id;

    @Column(name = "winner_alliance_id")
    private Long winnerAllianceId;

    @Column(name = "scheduled_start_at")
    private OffsetDateTime scheduledStartAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours;

    @Column(name = "request_expired_at", nullable = false)
    private OffsetDateTime requestExpiredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllianceMatchStatus status;

    @Column(name = "a_score", nullable = false)
    private Long aScore = 0L;

    @Column(name = "b_score", nullable = false)
    private Long bScore = 0L;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public static AllianceMatch request(Long allianceAId, Long allianceBId,
                                        int durationHours, OffsetDateTime requestExpiredAt) {
        if (allianceAId == null || allianceBId == null || allianceAId <= 0 || allianceBId <= 0) {
            throw new IllegalArgumentException("연합 ID는 올바른 양수여야 합니다.");
        }
        if (allianceAId.equals(allianceBId)) {
            throw new IllegalArgumentException("같은 연합끼리는 대결할 수 없습니다.");
        }
        if (durationHours < 1 || durationHours > 24) {
            throw new IllegalArgumentException("대결 시간은 1~24시간이어야 합니다.");
        }
        if (requestExpiredAt == null) {
            throw new IllegalArgumentException("대결 신청 마감 시각은 필수입니다.");
        }
        AllianceMatch match = new AllianceMatch();
        match.allianceAId = allianceAId;
        match.allianceBId = allianceBId;
        match.durationHours = durationHours;
        match.requestExpiredAt = requestExpiredAt;
        match.status = AllianceMatchStatus.REQUESTED;
        match.aScore = 0L;
        match.bScore = 0L;
        return match;
    }

    public static AllianceMatch request(Long allianceAId, Long allianceBId,
                                        int durationHours, OffsetDateTime requestExpiredAt,
                                        OffsetDateTime scheduledStartAt) {
        AllianceMatch match = request(allianceAId, allianceBId, durationHours, requestExpiredAt);
        match.scheduledStartAt = scheduledStartAt;
        return match;
    }

    public void schedule(OffsetDateTime acceptedAt) {
        if (scheduledStartAt == null || acceptedAt == null || !acceptedAt.isBefore(scheduledStartAt)) {
            throw new IllegalStateException("예약 시작 시각이 유효하지 않습니다.");
        }
        if (status != AllianceMatchStatus.REQUESTED || deletedAt != null
                || !acceptedAt.isBefore(requestExpiredAt)) {
            throw new IllegalStateException("유효한 대결 신청만 예약할 수 있습니다.");
        }
        status = AllianceMatchStatus.SCHEDULED;
    }

    public void startScheduled(OffsetDateTime now) {
        if (now == null || scheduledStartAt == null || now.isBefore(scheduledStartAt)
                || status != AllianceMatchStatus.SCHEDULED || deletedAt != null) {
            throw new IllegalStateException("예약 시작 조건이 충족되지 않았습니다.");
        }
        startedAt = now;
        endedAt = now.plusHours(durationHours);
        status = AllianceMatchStatus.IN_PROGRESS;
    }

    public void cancelScheduled() {
        if (status != AllianceMatchStatus.SCHEDULED || deletedAt != null) {
            throw new IllegalStateException("예약된 대결만 취소할 수 있습니다.");
        }
        status = AllianceMatchStatus.CANCELED;
    }

    public void accept(OffsetDateTime acceptedAt) {
        if (acceptedAt == null) {
            throw new IllegalArgumentException("대결 수락 시각은 필수입니다.");
        }
        if (status != AllianceMatchStatus.REQUESTED || deletedAt != null) {
            throw new IllegalStateException("대결 신청 대기 상태에서만 수락할 수 있습니다.");
        }
        if (!acceptedAt.isBefore(requestExpiredAt)) {
            throw new IllegalStateException("대결 신청 유효기간이 만료되었습니다.");
        }
        this.startedAt = acceptedAt;
        this.endedAt = acceptedAt.plusHours(durationHours);
        this.status = AllianceMatchStatus.IN_PROGRESS;
    }

    /** 시작과 같은 트랜잭션에서 정산에 사용할 학교와 순서를 한 번만 고정한다. */
    public void startWithRoster(OffsetDateTime now, long a1, long a2, long b1, long b2) {
        if (a1 <= 0 || a2 <= 0 || b1 <= 0 || b2 <= 0
                || a1 == a2 || a1 == b1 || a1 == b2 || a2 == b1 || a2 == b2 || b1 == b2) {
            throw new BusinessException(ErrorCode.MATCH_MEMBERS_INCOMPLETE, "대결 참가 학교는 서로 다른 네 학교여야 합니다.");
        }
        if (status == AllianceMatchStatus.SCHEDULED) {
            startScheduled(now);
        } else {
            accept(now);
        }
        aUniversity1Id = a1;
        aUniversity2Id = a2;
        bUniversity1Id = b1;
        bUniversity2Id = b2;
    }

    // 신청자 취소, 상대 거절, 기한 만료 모두 같은 CANCELED 상태로 귀결됨.
    public void cancelRequested() {
        if (status != AllianceMatchStatus.REQUESTED || deletedAt != null) {
            throw new IllegalStateException("대기 중인 대결 신청만 취소할 수 있습니다.");
        }
        this.status = AllianceMatchStatus.CANCELED;
    }

    public void finish(OffsetDateTime finishedAt, long aScore, long bScore) {
        if (finishedAt == null) {
            throw new IllegalArgumentException("대결 종료 시각은 필수입니다.");
        }
        if (status != AllianceMatchStatus.IN_PROGRESS || deletedAt != null) {
            throw new IllegalStateException("진행 중인 대결만 종료할 수 있습니다.");
        }
        if (endedAt == null || finishedAt.isBefore(endedAt)) {
            throw new IllegalStateException("대결 종료 시각이 지나지 않았습니다.");
        }
        if (aScore < 0 || bScore < 0) {
            throw new IllegalArgumentException("대결 점수는 음수일 수 없습니다.");
        }

        this.aScore = aScore;
        this.bScore = bScore;

        if (aScore > bScore) {
            this.winnerAllianceId = allianceAId;
        } else if (bScore > aScore) {
            this.winnerAllianceId = allianceBId;
        } else {
            this.winnerAllianceId = null;
        }

        this.status = AllianceMatchStatus.FINISHED;
    }
}
