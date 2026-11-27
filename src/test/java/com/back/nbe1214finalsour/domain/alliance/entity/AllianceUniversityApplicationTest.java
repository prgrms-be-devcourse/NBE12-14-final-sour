package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceUniversityApplicationTest {

    @Test
    @DisplayName("연합 가입 신청 시 PENDING 상태로 생성된다")
    void createApplication_success() {
        // given: 연합, 학교, 신청자 ID
        Long allianceId = 1L;
        Long universityId = 2L;
        Long userId = 3L;

        // when: 연합 가입 신청 생성
        AllianceUniversity application =
                AllianceUniversity.createApplication(
                        allianceId,
                        universityId,
                        userId
                );

        // then: 신청 정보 검증
        assertThat(application.getAllianceId())
                .isEqualTo(allianceId);

        assertThat(application.getUniversityId())
                .isEqualTo(universityId);

        assertThat(application.getApplicantUserId())
                .isEqualTo(userId);
        assertThat(application.getOrigin()).isEqualTo(AllianceMembershipOrigin.APPLICATION);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);

        assertThat(application.getApproverUserId())
                .isNull();

        assertThat(application.getTotalScore())
                .isZero();
    }

    @Test
    @DisplayName("창립 소속은 창립 출처로 기록된다")
    void createFounder_recordsOrigin() {
        AllianceUniversity founder = AllianceUniversity.createFounder(1L, 2L, 3L);
        assertThat(founder.getOrigin()).isEqualTo(AllianceMembershipOrigin.FOUNDER);
    }

    @Test
    @DisplayName("연합 가입 신청을 승인하면 ACCEPTED 상태로 변경된다")
    void approveApplication_success() {
        // given: 가입 신청이 PENDING 상태로 존재
        Long allianceId = 1L;
        Long universityId = 2L;
        Long applicantUserId = 3L;
        Long approverUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(
                        allianceId,
                        universityId,
                        applicantUserId
                );

        // 승인 전 상태 확인
        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);

        // when: 연합 가입 승인
        application.approve(approverUserId);

        // then: 승인 상태 및 승인자 확인
        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);

        assertThat(application.getApproverUserId())
                .isEqualTo(approverUserId);
    }

    @Test
    @DisplayName("이미 승인된 가입 신청은 다시 승인할 수 없다")
    void approveApplication_alreadyAccepted() {
        // given: 가입 신청 생성
        AllianceUniversity application =
                AllianceUniversity.createApplication(
                        1L,
                        2L,
                        3L
                );

        // 첫 번째 승인
        application.approve(4L);

        // when & then: 두 번째 승인 시 예외 발생
        assertThatThrownBy(() -> application.approve(5L))
                .isInstanceOf(IllegalStateException.class);

        // 기존 승인 상태와 승인자 정보 유지
        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);

        assertThat(application.getApproverUserId())
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("연합 가입 신청을 거절하면 REJECTED 상태로 변경된다")
    void rejectApplication_success() {
        // given: PENDING 상태의 가입 신청 생성
        AllianceUniversity application =
                AllianceUniversity.createApplication(
                        1L,
                        2L,
                        3L
                );

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);

        // when: 가입 신청 거절
        application.reject();

        // then: REJECTED 상태로 변경
        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.REJECTED);

        // 승인된 것이 아니므로 승인자 ID는 null
        assertThat(application.getApproverUserId())
                .isNull();

        // 신청자 정보는 유지
        assertThat(application.getApplicantUserId())
                .isEqualTo(3L);
    }
}