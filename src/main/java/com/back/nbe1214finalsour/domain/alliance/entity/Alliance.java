package com.back.nbe1214finalsour.domain.alliance.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "alliance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alliance extends BaseTimeEntity {
    public static final int MAX_SCHOOLS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "total_score", nullable = false)
    private Long totalScore = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllianceStatus status;

    @Column(name = "duration_hours", nullable = false, updatable = false)
    private Integer durationHours;

    @Column(name = "expired_at", nullable = false, updatable = false)
    private OffsetDateTime expiredAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public static Alliance create(String name, int durationHours, OffsetDateTime now) {
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw new IllegalArgumentException("연합 이름은 1~50자여야 합니다.");
        }

        if (durationHours < 6 || durationHours > 72) {
            throw new IllegalArgumentException("연합 유지 시간은 6~72시간이어야 합니다.");
        }

        if (now == null) {
            throw new IllegalArgumentException("현재 시각은 필수입니다.");
        }

        Alliance alliance = new Alliance();
        alliance.name = name.trim();
        alliance.totalScore = 0L;
        alliance.status = AllianceStatus.ACTIVE;
        alliance.durationHours = durationHours;
        alliance.expiredAt = now.plusHours(durationHours);

        return alliance;
    }

    // 만료 정리 서비스가 연합 행을 잠근 상태에서 호출한다.
    public void expire(OffsetDateTime now) {
        if (now == null || expiredAt == null || expiredAt.isAfter(now)) {
            throw new IllegalStateException("아직 만료 시각이 되지 않았습니다.");
        }
        if (status == AllianceStatus.EXPIRED) return;
        if (status != AllianceStatus.ACTIVE) {
            throw new IllegalStateException("진행 중인 대결을 먼저 정산해야 합니다.");
        }
        status = AllianceStatus.EXPIRED;
    }

    // 잠금 획득 후 호출한다. JPA 변경 감지로 상태가 저장된다.
    public void startMatch(OffsetDateTime now) {
        if (now == null) throw new IllegalArgumentException("현재 시각은 필수입니다.");
        if (status != AllianceStatus.ACTIVE || deletedAt != null
                || expiredAt == null || !expiredAt.isAfter(now)) {
            throw new IllegalStateException("현재 대결을 시작할 수 없는 연합입니다.");
        }
        status = AllianceStatus.IN_MATCH;
    }

    // 잠금 획득 후 호출한다. JPA 변경 감지로 상태가 저장된다.
    public void finishMatch() {
        if (status != AllianceStatus.IN_MATCH || deletedAt != null) {
            throw new IllegalStateException("대결 중인 연합만 종료할 수 있습니다.");
        }

        status = AllianceStatus.ACTIVE;
    }
}
