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
@Table(name = "alliance_university")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllianceUniversity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alliance_id", nullable = false)
    private Long allianceId;

    @Column(name = "university_id", nullable = false)
    private Long universityId;

    @Column(name = "applicant_user_id", nullable = false)
    private Long applicantUserId;

    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "total_score", nullable = false)
    private Long totalScore = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllianceUniversityStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private AllianceMembershipOrigin origin;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public static AllianceUniversity createFounder(
            Long allianceId,
            Long universityId,
            Long userId
    ) {
        if (allianceId == null || universityId == null || userId == null) {
            throw new IllegalArgumentException(
                    "연합, 학교, 사용자 ID는 필수입니다."
            );
        }

        AllianceUniversity member = new AllianceUniversity();
        member.allianceId = allianceId;
        member.universityId = universityId;
        member.applicantUserId = userId;
        member.approverUserId = userId;
        member.totalScore = 0L;
        member.status = AllianceUniversityStatus.ACCEPTED;
        member.origin = AllianceMembershipOrigin.FOUNDER;

        return member;
    }

    public static AllianceUniversity createApplication(
            Long allianceId,
            Long universityId,
            Long userId
    ) {
        if (allianceId == null || universityId == null || userId == null) {
            throw new IllegalArgumentException(
                    "연합, 학교, 사용자 ID는 필수입니다."
            );
        }

        AllianceUniversity application = new AllianceUniversity();

        application.allianceId = allianceId;
        application.universityId = universityId;
        application.applicantUserId = userId;
        application.approverUserId = null;
        application.totalScore = 0L;
        application.status = AllianceUniversityStatus.PENDING;
        application.origin = AllianceMembershipOrigin.APPLICATION;

        return application;
    }

    /** 초대를 보낸 회원과 초대받은 학교를 기록한다. */
    public static AllianceUniversity createInvitation(Long allianceId, Long universityId, Long inviterUserId) {
        AllianceUniversity invitation = createApplication(allianceId, universityId, inviterUserId);
        invitation.origin = AllianceMembershipOrigin.INVITATION;
        return invitation;
    }

    public void approve(Long approverUserId) {

        // 1. 승인자 ID 검증
        if (approverUserId == null) {
            throw new IllegalArgumentException(
                    "승인자 ID는 필수입니다."
            );
        }

        // 2. 승인 대기 상태인지 확인
        if (this.status != AllianceUniversityStatus.PENDING) {
            throw new IllegalStateException(
                    "승인 대기 중인 신청만 승인할 수 있습니다."
            );
        }

        // 3. 가입 상태 변경
        this.status = AllianceUniversityStatus.ACCEPTED;

        // 4. 승인자 기록
        this.approverUserId = approverUserId;
    }

    // 연합 만료 시 기존 가입 및 대기 신청을 각각 종료한다.
    public void expireMembership() {
        if (status == AllianceUniversityStatus.ACCEPTED) {
            status = AllianceUniversityStatus.LEFT;
        } else if (status == AllianceUniversityStatus.PENDING) {
            status = AllianceUniversityStatus.REJECTED;
        }
    }

    public void reject() {
        // 1. 승인 대기 상태인지 확인
        if (this.status != AllianceUniversityStatus.PENDING) {
            throw new IllegalStateException(
                    "승인 대기 중인 신청만 거절할 수 있습니다."
            );
        }

        // 2. 가입 신청 거절
        this.status = AllianceUniversityStatus.REJECTED;
    }
}