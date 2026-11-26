package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

    // 추가된 가입 신청·승인·거절 예외 검증용 고정 ID
    private static final Long USER_ID = 1L;
    private static final Long ALLIANCE_ID = 10L;
    private static final Long APPLICATION_ID = 20L;
    private static final Long UNIVERSITY_ID = 100L;

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

    // ===== 추가된 가입 신청·승인·거절 예외 시나리오 =====

    // ── 가입 신청: 기존 테스트에 없는 조회 실패 및 연합 상태 검증 ──

    @Test
    @DisplayName("가입 신청: 존재하지 않는 회원은 거부한다")
    void apply_missingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.USER_NOT_FOUND, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
        verify(allianceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("가입 신청: 비활성 회원은 거부한다")
    void apply_inactiveUser() {
        givenUser(USER_ID, UserStatus.WITHDRAWN);

        assertError(ErrorCode.ALLY_USER_NOT_ACTIVE, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
        verify(allianceRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("가입 신청: 없는 연합은 거부한다")
    void apply_missingAlliance() {
        User user = givenUser(USER_ID, UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(UNIVERSITY_ID);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_NOT_FOUND, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
    }

    @Test
    @DisplayName("가입 신청: 대결 중인 연합에는 신청할 수 없다")
    void apply_inMatchAlliance() {
        givenActiveApplicant();
        Alliance alliance = mock(Alliance.class);
        when(alliance.getStatus()).thenReturn(AllianceStatus.IN_MATCH);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        assertError(ErrorCode.ALLY_NOT_ACTIVE, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
        verify(allianceUniversityRepository, never()).save(any(AllianceUniversity.class));
    }

    @Test
    @DisplayName("가입 신청: 삭제된 연합에는 신청할 수 없다")
    void apply_deletedAlliance() {
        givenActiveApplicant();
        Alliance alliance = mock(Alliance.class);
        when(alliance.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(alliance.getDeletedAt()).thenReturn(OffsetDateTime.now());
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        assertError(ErrorCode.ALLY_NOT_ACTIVE, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
    }

    @Test
    @DisplayName("가입 신청: 만료 시각이 지난 연합에는 신청할 수 없다")
    void apply_expiredAlliance() {
        givenActiveApplicant();
        Alliance alliance = Alliance.create("만료된 연합", 6, OffsetDateTime.now().minusDays(2));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        assertError(ErrorCode.ALLY_NOT_ACTIVE, () -> allianceService.applyToAlliance(USER_ID, ALLIANCE_ID));
    }

    // ── 가입 승인: 누락된 조회/상태 경로 ──

    @Test
    @DisplayName("가입 승인: 승인자가 없으면 거부한다")
    void approve_missingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.USER_NOT_FOUND, () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 승인자가 비활성이면 거부한다")
    void approve_inactiveUser() {
        givenUser(USER_ID, UserStatus.WITHDRAWN);

        assertError(ErrorCode.ALLY_USER_NOT_ACTIVE, () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 신청 ID로 연합을 찾지 못하면 거부한다")
    void approve_missingAllianceIdByApplication() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        when(allianceUniversityRepository.findAllianceIdByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_APPLICATION_NOT_FOUND,
                () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 잠금 조회한 연합이 없으면 거부한다")
    void approve_missingAlliance() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        givenAllianceIdByApplication();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_NOT_FOUND, () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 잠금 후 신청이 사라졌으면 거부한다")
    void approve_missingApplicationAfterLock() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        givenAllianceIdByApplication();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(activeAlliance()));
        when(allianceUniversityRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_APPLICATION_NOT_FOUND,
                () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 삭제된 신청은 승인하지 않는다")
    void approve_deletedApplication() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        AllianceUniversity application = mock(AllianceUniversity.class);
        when(application.getDeletedAt()).thenReturn(OffsetDateTime.now());
        givenLockedApplication(application);

        assertError(ErrorCode.ALLY_APPLICATION_NOT_PENDING,
                () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 승인: 신청 학교가 다른 연합에 이미 가입했으면 거부한다")
    void approve_alreadyJoinedElsewhere() {
        User approver = givenUser(USER_ID, UserStatus.ACTIVE);
        when(approver.getUniversityId()).thenReturn(UNIVERSITY_ID);
        AllianceUniversity application = AllianceUniversity.createApplication(ALLIANCE_ID, 200L, 2L);
        AllianceUniversity founder = AllianceUniversity.createFounder(ALLIANCE_ID, UNIVERSITY_ID, USER_ID);
        givenLockedApplication(application);
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                ALLIANCE_ID, AllianceUniversityStatus.ACCEPTED)).thenReturn(List.of(founder));
        when(allianceUniversityRepository.existsByUniversityIdAndStatusAndDeletedAtIsNull(
                200L, AllianceUniversityStatus.ACCEPTED)).thenReturn(true);

        assertError(ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED,
                () -> allianceService.approveApplication(USER_ID, APPLICATION_ID));
        assertThat(application.getStatus()).isEqualTo(AllianceUniversityStatus.PENDING);
    }

    @Test
    @DisplayName("가입 승인: 정원 마감 시 이미 처리된 대기 항목은 건드리지 않는다")
    void approve_autoRejectSkipsHandledEntries() {
        User approver = givenUser(USER_ID, UserStatus.ACTIVE);
        when(approver.getUniversityId()).thenReturn(UNIVERSITY_ID);
        AllianceUniversity founder = AllianceUniversity.createFounder(ALLIANCE_ID, UNIVERSITY_ID, USER_ID);
        AllianceUniversity application = AllianceUniversity.createApplication(ALLIANCE_ID, 200L, 2L);
        AllianceUniversity alreadyRejected = AllianceUniversity.createApplication(ALLIANCE_ID, 300L, 3L);
        alreadyRejected.reject();
        AllianceUniversity otherPending = AllianceUniversity.createApplication(ALLIANCE_ID, 400L, 4L);
        givenLockedApplication(application);
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                ALLIANCE_ID, AllianceUniversityStatus.ACCEPTED)).thenReturn(List.of(founder));
        // 동시 요청으로 조회 결과에 현재 신청이나 이미 처리된 항목이 섞였다고 가정한다.
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                ALLIANCE_ID, AllianceUniversityStatus.PENDING))
                .thenReturn(List.of(application, alreadyRejected, otherPending));

        AllianceUniversity result = allianceService.approveApplication(USER_ID, APPLICATION_ID);

        assertThat(result).isSameAs(application);
        assertThat(application.getStatus()).isEqualTo(AllianceUniversityStatus.ACCEPTED);
        assertThat(alreadyRejected.getStatus()).isEqualTo(AllianceUniversityStatus.REJECTED);
        assertThat(otherPending.getStatus()).isEqualTo(AllianceUniversityStatus.REJECTED);
    }

    // ── 가입 거절: 누락된 조회/상태 경로 ──

    @Test
    @DisplayName("가입 거절: 거절자가 없으면 거부한다")
    void reject_missingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.USER_NOT_FOUND, () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 거절: 거절자가 비활성이면 거부한다")
    void reject_inactiveUser() {
        givenUser(USER_ID, UserStatus.WITHDRAWN);

        assertError(ErrorCode.ALLY_USER_NOT_ACTIVE, () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 거절: 신청 ID로 연합을 찾지 못하면 거부한다")
    void reject_missingAllianceIdByApplication() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        when(allianceUniversityRepository.findAllianceIdByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_APPLICATION_NOT_FOUND,
                () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 거절: 잠금 조회한 연합이 없으면 거부한다")
    void reject_missingAlliance() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        givenAllianceIdByApplication();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_NOT_FOUND, () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 거절: 잠금 후 신청이 사라졌으면 거부한다")
    void reject_missingApplicationAfterLock() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        givenAllianceIdByApplication();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(activeAlliance()));
        when(allianceUniversityRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_APPLICATION_NOT_FOUND,
                () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    @Test
    @DisplayName("가입 거절: 삭제된 신청은 거절하지 않는다")
    void reject_deletedApplication() {
        givenUser(USER_ID, UserStatus.ACTIVE);
        AllianceUniversity application = mock(AllianceUniversity.class);
        when(application.getDeletedAt()).thenReturn(OffsetDateTime.now());
        givenLockedApplication(application);

        assertError(ErrorCode.ALLY_APPLICATION_NOT_PENDING,
                () -> allianceService.rejectApplication(USER_ID, APPLICATION_ID));
    }

    // ── 공통 ──

    private User givenUser(Long userId, UserStatus status) {
        User user = mock(User.class);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(status);
        return user;
    }

    private void givenActiveApplicant() {
        User user = givenUser(USER_ID, UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(UNIVERSITY_ID);
    }

    private Alliance activeAlliance() {
        return Alliance.create("정상 연합", 24, OffsetDateTime.now());
    }

    private void givenAllianceIdByApplication() {
        when(allianceUniversityRepository.findAllianceIdByApplicationId(APPLICATION_ID))
                .thenReturn(Optional.of(ALLIANCE_ID));
    }

    private void givenLockedApplication(AllianceUniversity application) {
        givenAllianceIdByApplication();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(activeAlliance()));
        when(allianceUniversityRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
    }

    private static void assertError(ErrorCode expected, ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", expected);
    }
}
