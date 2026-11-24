package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllianceServiceApplicationTest {

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AllianceService allianceService;

    // ===== 가입 신청 테스트 =====

    @Test
    @DisplayName("활성 회원이 연합 가입을 신청하면 PENDING으로 저장된다")
    void applyToAlliance_success() {
        Long userId = 3L;
        Long universityId = 2L;
        Long allianceId = 1L;

        User user = mock(User.class);
        Alliance alliance = createAlliance();

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(user.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId())
                .thenReturn(universityId);

        when(allianceRepository.findByIdForUpdate(allianceId))
                .thenReturn(Optional.of(alliance));

        when(allianceUniversityRepository.save(any(AllianceUniversity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AllianceUniversity result =
                allianceService.applyToAlliance(userId, allianceId);

        assertThat(result.getAllianceId()).isEqualTo(allianceId);
        assertThat(result.getUniversityId()).isEqualTo(universityId);
        assertThat(result.getApplicantUserId()).isEqualTo(userId);
        assertThat(result.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);
        verify(allianceRepository).findByIdForUpdate(allianceId);
    }

    @Test
    @DisplayName("이미 가입한 학교는 중복 신청할 수 없다")
    void applyToAlliance_alreadyJoined() {
        Long userId = 3L;
        Long universityId = 2L;
        Long allianceId = 1L;

        User user = mock(User.class);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(user.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId())
                .thenReturn(universityId);

        when(allianceRepository.findByIdForUpdate(allianceId))
                .thenReturn(Optional.of(createAlliance()));

        when(allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(true);

        assertThatThrownBy(() ->
                allianceService.applyToAlliance(userId, allianceId)
        ).isInstanceOf(BusinessException.class);

        verify(allianceUniversityRepository, never())
                .save(any(AllianceUniversity.class));
    }

    @Test
    @DisplayName("이미 신청 대기 중인 학교는 중복 신청할 수 없다")
    void applyToAlliance_alreadyPending() {
        Long userId = 3L;
        Long universityId = 2L;
        Long allianceId = 1L;

        User user = mock(User.class);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(user.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId())
                .thenReturn(universityId);

        when(allianceRepository.findByIdForUpdate(allianceId))
                .thenReturn(Optional.of(createAlliance()));

        when(allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(false);

        when(allianceUniversityRepository
                .existsByAllianceIdAndUniversityIdAndStatusAndDeletedAtIsNull(
                        allianceId,
                        universityId,
                        AllianceUniversityStatus.PENDING
                ))
                .thenReturn(true);

        assertThatThrownBy(() ->
                allianceService.applyToAlliance(userId, allianceId)
        ).isInstanceOf(BusinessException.class);

        verify(allianceUniversityRepository, never())
                .save(any(AllianceUniversity.class));
    }

    @Test
    @DisplayName("연합에 2개 학교가 가입했다면 추가 신청할 수 없다")
    void applyToAlliance_full() {
        Long userId = 3L;
        Long universityId = 3L;
        Long allianceId = 1L;

        User user = mock(User.class);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
        when(user.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId())
                .thenReturn(universityId);

        when(allianceRepository.findByIdForUpdate(allianceId))
                .thenReturn(Optional.of(createAlliance()));

        when(allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(false);

        when(allianceUniversityRepository
                .existsByAllianceIdAndUniversityIdAndStatusAndDeletedAtIsNull(
                        allianceId,
                        universityId,
                        AllianceUniversityStatus.PENDING
                ))
                .thenReturn(false);

        when(allianceUniversityRepository
                .countByAllianceIdAndStatusAndDeletedAtIsNull(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(2L);

        assertThatThrownBy(() ->
                allianceService.applyToAlliance(userId, allianceId)
        ).isInstanceOf(BusinessException.class);

        verify(allianceUniversityRepository, never())
                .save(any(AllianceUniversity.class));
    }

    // ===== 가입 승인 테스트 =====

    @Test
    @DisplayName("연합 소속 회원이 가입 신청을 승인하면 ACCEPTED가 된다")
    void approveApplication_success() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long approverUserId = 4L;
        Long approverUniversityId = 3L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity founder =
                AllianceUniversity.createFounder(
                        allianceId, approverUniversityId, approverUserId
                );

        User approver = mock(User.class);

        when(userRepository.findById(approverUserId))
                .thenReturn(Optional.of(approver));
        when(approver.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(approver.getUniversityId())
                .thenReturn(approverUniversityId);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(founder));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.PENDING
                ))
                .thenReturn(List.of());

        AllianceUniversity result =
                allianceService.approveApplication(
                        approverUserId, applicationId
                );

        assertThat(result.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);
        assertThat(result.getApproverUserId())
                .isEqualTo(approverUserId);

        // 락을 획득한 후 가입 신청을 조회해야 한다
        InOrder order = inOrder(
                allianceRepository,
                allianceUniversityRepository
        );

        order.verify(allianceRepository)
                .findByIdForUpdate(allianceId);

        order.verify(allianceUniversityRepository)
                .findById(applicationId);
    }

    @Test
    @DisplayName("연합 소속이 아닌 회원은 승인할 수 없다")
    void approveApplication_notMember() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long approverUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity founder =
                AllianceUniversity.createFounder(allianceId, 3L, 3L);

        User approver = mock(User.class);

        when(userRepository.findById(approverUserId))
                .thenReturn(Optional.of(approver));
        when(approver.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(approver.getUniversityId())
                .thenReturn(4L);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(founder));

        assertThatThrownBy(() ->
                allianceService.approveApplication(
                        approverUserId, applicationId
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);
        assertThat(application.getApproverUserId()).isNull();
    }

    @Test
    @DisplayName("연합 정원이 가득 차면 승인할 수 없다")
    void approveApplication_full() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long approverUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity member1 =
                AllianceUniversity.createFounder(allianceId, 3L, 4L);

        AllianceUniversity member2 =
                AllianceUniversity.createFounder(allianceId, 4L, 6L);

        User approver = mock(User.class);

        when(userRepository.findById(approverUserId))
                .thenReturn(Optional.of(approver));
        when(approver.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(approver.getUniversityId())
                .thenReturn(3L);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(member1, member2));

        assertThatThrownBy(() ->
                allianceService.approveApplication(
                        approverUserId, applicationId
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);
        assertThat(application.getApproverUserId()).isNull();
    }

    @Test
    @DisplayName("이미 승인한 신청은 다시 승인할 수 없다")
    void approveApplication_alreadyAccepted() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long approverUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 3L);

        application.approve(5L);

        User approver = mock(User.class);

        when(userRepository.findById(approverUserId))
                .thenReturn(Optional.of(approver));
        when(approver.getStatus())
                .thenReturn(UserStatus.ACTIVE);

        mockApplicationLookup(applicationId, allianceId, application);

        assertThatThrownBy(() ->
                allianceService.approveApplication(
                        approverUserId, applicationId
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);
        assertThat(application.getApproverUserId())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("정원이 차면 다른 대기 신청은 자동 거절된다")
    void approveApplication_autoRejectPending() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long approverUserId = 4L;

        AllianceUniversity founder =
                AllianceUniversity.createFounder(allianceId, 1L, 4L);

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity otherPending =
                AllianceUniversity.createApplication(allianceId, 3L, 6L);

        User approver = mock(User.class);

        when(userRepository.findById(approverUserId))
                .thenReturn(Optional.of(approver));
        when(approver.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(approver.getUniversityId())
                .thenReturn(1L);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(founder));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.PENDING
                ))
                .thenReturn(List.of(otherPending));

        AllianceUniversity result =
                allianceService.approveApplication(
                        approverUserId, applicationId
                );

        assertThat(result.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);

        assertThat(otherPending.getStatus())
                .isEqualTo(AllianceUniversityStatus.REJECTED);
    }

    // ===== 가입 거절 테스트 =====

    @Test
    @DisplayName("연합 소속 회원이 가입 신청을 거절하면 REJECTED가 된다")
    void rejectApplication_success() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long rejecterUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity founder =
                AllianceUniversity.createFounder(allianceId, 3L, rejecterUserId);

        User rejecter = mock(User.class);

        when(userRepository.findById(rejecterUserId))
                .thenReturn(Optional.of(rejecter));
        when(rejecter.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(rejecter.getUniversityId())
                .thenReturn(3L);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(founder));

        AllianceUniversity result =
                allianceService.rejectApplication(
                        rejecterUserId, applicationId
                );

        assertThat(result.getStatus())
                .isEqualTo(AllianceUniversityStatus.REJECTED);
        assertThat(result.getApproverUserId()).isNull();
        assertThat(result.getApplicantUserId()).isEqualTo(5L);
        assertThat(result.getUniversityId()).isEqualTo(2L);

        // 거절도 락 획득 후 신청 조회
        InOrder order = inOrder(
                allianceRepository,
                allianceUniversityRepository
        );

        order.verify(allianceRepository)
                .findByIdForUpdate(allianceId);

        order.verify(allianceUniversityRepository)
                .findById(applicationId);
    }

    @Test
    @DisplayName("연합 소속이 아닌 회원은 신청을 거절할 수 없다")
    void rejectApplication_notMember() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long rejecterUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 5L);

        AllianceUniversity founder =
                AllianceUniversity.createFounder(allianceId, 3L, 3L);

        User rejecter = mock(User.class);

        when(userRepository.findById(rejecterUserId))
                .thenReturn(Optional.of(rejecter));
        when(rejecter.getStatus())
                .thenReturn(UserStatus.ACTIVE);
        when(rejecter.getUniversityId())
                .thenReturn(4L);

        mockApplicationLookup(applicationId, allianceId, application);

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(founder));

        assertThatThrownBy(() ->
                allianceService.rejectApplication(
                        rejecterUserId, applicationId
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.PENDING);
        assertThat(application.getApproverUserId()).isNull();
    }

    @Test
    @DisplayName("이미 거절된 신청은 다시 거절할 수 없다")
    void rejectApplication_alreadyRejected() {
        Long allianceId = 1L;
        Long applicationId = 10L;
        Long rejecterUserId = 4L;

        AllianceUniversity application =
                AllianceUniversity.createApplication(allianceId, 2L, 3L);

        application.reject();

        User rejecter = mock(User.class);

        when(userRepository.findById(rejecterUserId))
                .thenReturn(Optional.of(rejecter));
        when(rejecter.getStatus())
                .thenReturn(UserStatus.ACTIVE);

        mockApplicationLookup(applicationId, allianceId, application);

        assertThatThrownBy(() ->
                allianceService.rejectApplication(
                        rejecterUserId, applicationId
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(application.getStatus())
                .isEqualTo(AllianceUniversityStatus.REJECTED);
        assertThat(application.getApplicantUserId())
                .isEqualTo(3L);
    }

    // ===== 테스트 공통 메서드 =====

    private Alliance createAlliance() {
        return Alliance.create(
                "테스트 연합",
                24,
                OffsetDateTime.now()
        );
    }

    private void mockApplicationLookup(
            Long applicationId,
            Long allianceId,
            AllianceUniversity application
    ) {
        // 가입 신청에서 연합 ID 조회
        when(allianceUniversityRepository
                .findAllianceIdByApplicationId(applicationId))
                .thenReturn(Optional.of(allianceId));

        // 연합 비관적 락
        when(allianceRepository.findByIdForUpdate(allianceId))
                .thenReturn(Optional.of(createAlliance()));

        // 락 획득 후 신청 조회
        when(allianceUniversityRepository.findById(applicationId))
                .thenReturn(Optional.of(application));
    }
}