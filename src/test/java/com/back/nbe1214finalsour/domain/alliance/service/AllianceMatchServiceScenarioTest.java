package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 대결의 입력 검증, 예약 시작, 취소 및 연합 만료 경계 상황을 검증한다.
 * 기존 AllianceMatchServiceTest의 기본 동작 검증과 역할을 분리한다.
 */
@ExtendWith(MockitoExtension.class)
class AllianceMatchServiceScenarioTest {
    private static final Long A = 10L;
    private static final Long B = 20L;
    private static final Long MATCH = 50L;
    private static final Long USER = 3L;

    @Mock AllianceRepository allianceRepository;
    @Mock AllianceUniversityRepository allianceUniversityRepository;
    @Mock AllianceMatchRepository allianceMatchRepository;
    @Mock UserRepository userRepository;
    @InjectMocks AllianceMatchService service;

    private Alliance alliance(String name) {
        return Alliance.create(name, 48, OffsetDateTime.now().minusHours(1));
    }

    private AllianceMatch requested() {
        return AllianceMatch.request(A, B, 6, OffsetDateTime.now().plusHours(10));
    }

    private AllianceMatch scheduled(OffsetDateTime start) {
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().plusHours(10), start);
        match.schedule(start.minusMinutes(30));
        return match;
    }

    private List<AllianceUniversity> aMembers() {
        return List.of(AllianceUniversity.createFounder(A, 100L, 1L),
                AllianceUniversity.createFounder(A, 101L, 2L));
    }

    private List<AllianceUniversity> bMembers() {
        return List.of(AllianceUniversity.createFounder(B, 200L, 3L),
                AllianceUniversity.createFounder(B, 201L, 4L));
    }

    private User activeUser(long universityId) {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(universityId);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        return user;
    }

    private void lockAlliances(Alliance a, Alliance b) {
        when(allianceRepository.findByIdForUpdate(A)).thenReturn(Optional.of(a));
        when(allianceRepository.findByIdForUpdate(B)).thenReturn(Optional.of(b));
    }

    private void pairLookup() {
        when(allianceMatchRepository.findPairById(MATCH))
                .thenReturn(Optional.of(new AllianceMatchPair(A, B)));
    }

    private void pairAndLockedMatch(AllianceMatch match) {
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
    }

    private void memberLists() {
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                A, AllianceUniversityStatus.ACCEPTED)).thenReturn(aMembers());
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                B, AllianceUniversityStatus.ACCEPTED)).thenReturn(bMembers());
    }

    private void validRequestSetup() {
        activeUser(100L);
        lockAlliances(alliance("연합A"), alliance("연합B"));
        memberLists();
    }

    private void validAcceptSetup(AllianceMatch match, Alliance a, Alliance b) {
        activeUser(200L);
        pairLookup();
        lockAlliances(a, b);
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        memberLists();
    }

    private void assertError(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void requestMatch_rejectsInvalidParametersBeforeQueryingRepositories() {
        Long[][] ids = {{null, B}, {0L, B}, {-1L, B}, {A, null}, {A, 0L}, {A, -1L}, {A, A}};
        for (Long[] pair : ids) {
            assertError(ErrorCode.COMMON_INVALID_INPUT,
                    () -> service.requestMatch(USER, pair[0], pair[1], 6));
        }
        for (int duration : new int[]{0, -1, 25}) {
            assertError(ErrorCode.COMMON_INVALID_INPUT,
                    () -> service.requestMatch(USER, A, B, duration));
        }
        verifyNoInteractions(allianceRepository, allianceMatchRepository, userRepository);
    }

    @Test
    void requestMatch_rejectsMissingUser() {
        when(userRepository.findById(USER)).thenReturn(Optional.empty());
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.requestMatch(USER, A, B, 6));
        verifyNoInteractions(allianceRepository);
    }

    @Test
    void requestMatch_rejectsInactiveUser() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.SUSPENDED);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        assertError(ErrorCode.ALLY_USER_NOT_ACTIVE, () -> service.requestMatch(USER, A, B, 6));
        verifyNoInteractions(allianceRepository);
    }

    @Test
    void requestMatch_rejectsDeletedUser() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getDeletedAt()).thenReturn(OffsetDateTime.now().minusDays(1));
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        assertError(ErrorCode.ALLY_USER_NOT_ACTIVE, () -> service.requestMatch(USER, A, B, 6));
    }

    @Test
    void requestMatch_rejectsConflictingScheduleOnAllianceA() {
        validRequestSetup();
        when(allianceMatchRepository.existsScheduledForAlliance(A)).thenReturn(true);
        assertError(ErrorCode.MATCH_SCHEDULE_CONFLICT, () -> service.requestMatch(USER, A, B, 6));
        verify(allianceMatchRepository, never()).save(any());
    }

    @Test
    void requestMatch_rejectsConflictingScheduleOnAllianceB() {
        validRequestSetup();
        when(allianceMatchRepository.existsScheduledForAlliance(A)).thenReturn(false);
        when(allianceMatchRepository.existsScheduledForAlliance(B)).thenReturn(true);
        assertError(ErrorCode.MATCH_SCHEDULE_CONFLICT, () -> service.requestMatch(USER, A, B, 6));
        verify(allianceMatchRepository, never()).save(any());
    }

    @Test
    void requestMatch_rejectsStartTimeInPast() {
        validRequestSetup();
        assertError(ErrorCode.MATCH_INVALID_SCHEDULE,
                () -> service.requestMatch(USER, A, B, 6, OffsetDateTime.now().minusMinutes(5)));
    }

    @Test
    void requestMatch_rejectsScheduledMatchBeyondAllianceExpiry() {
        validRequestSetup();
        assertError(ErrorCode.MATCH_INVALID_SCHEDULE,
                () -> service.requestMatch(USER, A, B, 6, OffsetDateTime.now().plusHours(45)));
    }

    @Test
    void requestMatch_storesValidScheduledTime() {
        validRequestSetup();
        OffsetDateTime start = OffsetDateTime.now().plusHours(2);
        when(allianceMatchRepository.save(any(AllianceMatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AllianceMatch result = service.requestMatch(USER, A, B, 6, start);
        assertThat(result.getScheduledStartAt()).isEqualTo(start);
        assertThat(result.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
        assertThat(result.getRequestExpiredAt()).isBeforeOrEqualTo(start);
    }

    @Test
    void acceptMatch_rejectsScheduledTimeAlreadyPassed() {
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().plusHours(10), OffsetDateTime.now().minusMinutes(1));
        validAcceptSetup(match, alliance("연합A"), alliance("연합B"));
        assertError(ErrorCode.MATCH_INVALID_SCHEDULE, () -> service.acceptMatch(USER, MATCH));
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
    }

    @Test
    void acceptMatch_rejectsScheduledTimePastExpiry() {
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().plusHours(10), OffsetDateTime.now().plusHours(4));
        Alliance shortAlliance = Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(1));
        validAcceptSetup(match, shortAlliance, alliance("연합B"));
        assertError(ErrorCode.MATCH_INVALID_SCHEDULE, () -> service.acceptMatch(USER, MATCH));
    }

    @Test
    void acceptMatch_rejectsImmediateStartWhenAllianceTimeInsufficient() {
        AllianceMatch match = requested();
        Alliance shortAlliance = Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(1));
        validAcceptSetup(match, shortAlliance, alliance("연합B"));
        assertError(ErrorCode.MATCH_INSUFFICIENT_TIME, () -> service.acceptMatch(USER, MATCH));
    }

    @Test
    void acceptMatch_schedulesInsteadOfStartingImmediately() {
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().plusHours(10), OffsetDateTime.now().plusHours(2));
        Alliance a = alliance("연합A");
        Alliance b = alliance("연합B");
        validAcceptSetup(match, a, b);
        assertThat(service.acceptMatch(USER, MATCH).getStatus())
                .isEqualTo(AllianceMatchStatus.SCHEDULED);
        assertThat(match.getStartedAt()).isNull();
        assertThat(a.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        assertThat(b.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
    }

    @Test
    void startScheduledMatch_ignoresInvalidId() {
        service.startScheduledMatch(null);
        service.startScheduledMatch(0L);
        verifyNoInteractions(allianceRepository, allianceMatchRepository);
    }

    @Test
    void startScheduledMatch_ignoresMissingPair() {
        when(allianceMatchRepository.findPairById(MATCH)).thenReturn(Optional.empty());
        service.startScheduledMatch(MATCH);
        verifyNoInteractions(allianceRepository);
    }

    @Test
    void startScheduledMatch_ignoresMissingMatchAfterLockingAlliances() {
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.empty());
        service.startScheduledMatch(MATCH);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_doesNotStartBeforeScheduledTime() {
        AllianceMatch match = scheduled(OffsetDateTime.now().plusHours(2));
        pairAndLockedMatch(match);
        service.startScheduledMatch(MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.SCHEDULED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenAllianceAlreadyExpired() {
        AllianceMatch match = scheduled(OffsetDateTime.now().minusMinutes(1));
        pairLookup();
        lockAlliances(Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(7)), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        service.startScheduledMatch(MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsIfRosterIsIncomplete() {
        AllianceMatch match = scheduled(OffsetDateTime.now().minusMinutes(1));
        pairAndLockedMatch(match);
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                A, AllianceUniversityStatus.ACCEPTED)).thenReturn(aMembers());
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                B, AllianceUniversityStatus.ACCEPTED)).thenReturn(List.of(bMembers().get(0)));
        service.startScheduledMatch(MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void startScheduledMatch_startsAndFreezesRosterWhenDue() {
        AllianceMatch match = scheduled(OffsetDateTime.now().minusMinutes(1));
        Alliance a = alliance("연합A");
        Alliance b = alliance("연합B");
        pairLookup();
        lockAlliances(a, b);
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        memberLists();
        service.startScheduledMatch(MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(match.getAUniversity1Id()).isEqualTo(100L);
        assertThat(match.getBUniversity2Id()).isEqualTo(201L);
        assertThat(a.getStatus()).isEqualTo(AllianceStatus.IN_MATCH);
        assertThat(b.getStatus()).isEqualTo(AllianceStatus.IN_MATCH);
    }

    @Test
    void cancelMatch_rejectsAlreadyCanceledMatch() {
        AllianceMatch match = requested();
        match.cancelRequested();
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        pairAndLockedMatch(match);
        assertError(ErrorCode.MATCH_NOT_PENDING, () -> service.cancelMatch(USER, MATCH));
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void cancelMatch_rejectsNonMember() {
        AllianceMatch match = requested();
        activeUser(999L);
        pairAndLockedMatch(match);
        memberLists();
        assertError(ErrorCode.ALLY_NOT_MEMBER, () -> service.cancelMatch(USER, MATCH));
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
    }

    @Test
    void cancelMatch_allowsMemberOfAllianceB() {
        AllianceMatch match = requested();
        activeUser(200L);
        pairAndLockedMatch(match);
        memberLists();
        service.cancelMatch(USER, MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void cancelExpiredRequest_doesNothingForInvalidId() {
        service.cancelExpiredRequest(null);
        service.cancelExpiredRequest(-1L);
        verifyNoInteractions(allianceMatchRepository);
    }

    @Test
    void cancelExpiredRequest_keepsUnexpiredRequest() {
        AllianceMatch match = requested();
        pairAndLockedMatch(match);
        service.cancelExpiredRequest(MATCH);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
    }

    @Test
    void cancelRequestedForAllianceExpiry_ignoresUnknownMatch() {
        when(allianceMatchRepository.findPairById(MATCH)).thenReturn(Optional.empty());
        service.cancelRequestedForAllianceExpiry(MATCH, A);
        verifyNoInteractions(allianceRepository);
    }

    @Test
    void cancelRequestedForAllianceExpiry_ignoresUnrelatedAlliance() {
        pairLookup();
        service.cancelRequestedForAllianceExpiry(MATCH, 999L);
        verifyNoInteractions(allianceRepository);
    }

    @Test
    void cancelRequestedForAllianceExpiry_keepsRequestIfAllianceNotExpired() {
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        service.cancelRequestedForAllianceExpiry(MATCH, A);
        verify(allianceMatchRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void cancelRequestedForAllianceExpiry_cancelsRequestWhenAllianceAExpired() {
        AllianceMatch match = requested();
        pairLookup();
        lockAlliances(Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(7)), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        service.cancelRequestedForAllianceExpiry(MATCH, A);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void cancelRequestedForAllianceExpiry_cancelsRequestWhenAllianceBExpired() {
        AllianceMatch match = requested();
        pairLookup();
        lockAlliances(alliance("연합A"), Alliance.create("연합B", 6, OffsetDateTime.now().minusHours(7)));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        service.cancelRequestedForAllianceExpiry(MATCH, B);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void cancelRequestedForAllianceExpiry_preservesInProgressMatch() {
        AllianceMatch match = requested();
        match.accept(OffsetDateTime.now());
        pairLookup();
        lockAlliances(Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(7)), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        service.cancelRequestedForAllianceExpiry(MATCH, A);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
    }

    @Test
    void getMatch_rejectsInvalidIdAndUnknownMatch() {
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.getMatch(null));
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.getMatch(0L));
        when(allianceMatchRepository.findById(MATCH)).thenReturn(Optional.empty());
        assertError(ErrorCode.MATCH_NOT_FOUND, () -> service.getMatch(MATCH));
    }


    // ===== 잠금 조회 실패 및 예약 시작 예외 상황 =====

    private Alliance activeAlliance(String name) {
        return Alliance.create(name, 48, OffsetDateTime.now().minusHours(1));
    }

    private AllianceMatch scheduledDue() {
        OffsetDateTime start = OffsetDateTime.now().minusMinutes(2);
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().plusHours(10), start);
        match.schedule(start.minusMinutes(30));
        return match;
    }

    private void loadScheduled(AllianceMatch match, Alliance a, Alliance b) {
        pairLookup();
        lockAlliances(a, b);
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(match));
    }

    @Test
    void startScheduledMatch_rejectsMissingFirstLockedAlliance() {
        pairLookup();
        when(allianceRepository.findByIdForUpdate(A)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_NOT_FOUND,
                () -> service.startScheduledMatch(MATCH));

        verify(allianceRepository, never()).findByIdForUpdate(B);
        verify(allianceMatchRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void startScheduledMatch_rejectsMissingSecondLockedAlliance() {
        pairLookup();
        when(allianceRepository.findByIdForUpdate(A))
                .thenReturn(Optional.of(activeAlliance("연합A")));
        when(allianceRepository.findByIdForUpdate(B)).thenReturn(Optional.empty());

        assertError(ErrorCode.ALLY_NOT_FOUND,
                () -> service.startScheduledMatch(MATCH));

        verify(allianceMatchRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void acceptMatch_rejectsMissingLockedMatch() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        pairLookup();
        lockAlliances(activeAlliance("연합A"), activeAlliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.empty());

        assertError(ErrorCode.MATCH_NOT_FOUND,
                () -> service.acceptMatch(USER, MATCH));
    }

    @Test
    void startScheduledMatch_cancelsWhenFirstAllianceNotActive() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.IN_MATCH);
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenAllianceDeleted() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(a.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(5));
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenAllianceHasNoExpiryTime() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        // Mockito 기본 반환값 null: a.getExpiredAt() == null
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenFirstAllianceHasTooFewMembers() {
        AllianceMatch match = scheduledDue();
        loadScheduled(match, activeAlliance("연합A"), activeAlliance("연합B"));
        List<AllianceUniversity> aMembers = List.of(
                AllianceUniversity.createFounder(A, 100L, 1L));
        List<AllianceUniversity> bMembers = List.of(
                AllianceUniversity.createFounder(B, 200L, 3L),
                AllianceUniversity.createFounder(B, 201L, 4L));
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                A, AllianceUniversityStatus.ACCEPTED)).thenReturn(aMembers);
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                B, AllianceUniversityStatus.ACCEPTED)).thenReturn(bMembers);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void startScheduledMatch_rejectsMismatchedPairUnderLock() {
        pairLookup();
        lockAlliances(activeAlliance("연합A"), activeAlliance("연합B"));
        AllianceMatch differentPair = AllianceMatch.request(A, 999L, 6,
                OffsetDateTime.now().plusHours(10));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(differentPair));

        assertError(ErrorCode.COMMON_INVALID_INPUT,
                () -> service.startScheduledMatch(MATCH));

        verifyNoInteractions(allianceUniversityRepository);
    }

    // ===== JaCoCo 분기 보완: 만료 검사, 예약 시작, 잠금 후 상태 변경 =====

    @Test
    void acceptMatch_rejectsRequestWithoutExpiration() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getStatus()).thenReturn(AllianceMatchStatus.REQUESTED);
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));

        assertError(ErrorCode.MATCH_REQUEST_EXPIRED, () -> service.acceptMatch(USER, MATCH));
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void acceptMatch_rejectsSoftDeletedRequestedMatch() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));

        assertError(ErrorCode.MATCH_NOT_PENDING, () -> service.acceptMatch(USER, MATCH));
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void requestMatch_rejectsSecondAllianceWithMissingExpiration() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        Alliance b = mock(Alliance.class);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        lockAlliances(alliance("연합A"), b);

        assertError(ErrorCode.ALLY_NOT_ACTIVE, () -> service.requestMatch(USER, A, B, 6));
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void requestMatch_rejectsDeletedSecondAlliance() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        Alliance b = mock(Alliance.class);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        lockAlliances(alliance("연합A"), b);

        assertError(ErrorCode.ALLY_NOT_ACTIVE, () -> service.requestMatch(USER, A, B, 6));
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_skipsMatchThatIsStillRequested() {
        AllianceMatch match = requested();
        pairAndLockedMatch(match);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_skipsScheduledMatchWithoutStartTime() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getStatus()).thenReturn(AllianceMatchStatus.SCHEDULED);
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));

        service.startScheduledMatch(MATCH);

        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenSecondAllianceIsNotActive() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getStatus()).thenReturn(AllianceStatus.IN_MATCH);
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenSecondAllianceIsDeleted() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenSecondAllianceHasNoExpiry() {
        Alliance a = mock(Alliance.class);
        Alliance b = mock(Alliance.class);
        when(a.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(b.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(a.getExpiredAt()).thenReturn(OffsetDateTime.now().plusHours(20));
        AllianceMatch match = scheduledDue();
        loadScheduled(match, a, b);

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void startScheduledMatch_cancelsWhenRemainingTimeIsShorterThanMatch() {
        AllianceMatch match = scheduledDue();
        Alliance a = Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(2));
        loadScheduled(match, a, alliance("연합B"));

        service.startScheduledMatch(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void cancelExpiredRequest_ignoresMissingPair() {
        when(allianceMatchRepository.findPairById(MATCH)).thenReturn(Optional.empty());

        service.cancelExpiredRequest(MATCH);

        verifyNoInteractions(allianceRepository, allianceUniversityRepository);
    }

    @Test
    void cancelExpiredRequest_ignoresMissingMatchAfterLock() {
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.empty());

        service.cancelExpiredRequest(MATCH);

        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void cancelExpiredRequest_cancelsRequestAfterDeadline() {
        AllianceMatch match = AllianceMatch.request(A, B, 6,
                OffsetDateTime.now().minusMinutes(1));
        pairAndLockedMatch(match);

        service.cancelExpiredRequest(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void cancelExpiredRequest_doesNotCancelPreviouslyCanceledMatch() {
        AllianceMatch match = requested();
        match.cancelRequested();
        pairAndLockedMatch(match);

        service.cancelExpiredRequest(MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
    }

    @Test
    void cancelRequestedForAllianceExpiry_skipsMissingMatchAfterLock() {
        pairLookup();
        lockAlliances(Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(7)),
                alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.empty());

        service.cancelRequestedForAllianceExpiry(MATCH, A);

        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    void cancelRequestedForAllianceExpiry_skipsAllianceWithoutExpiration() {
        pairLookup();
        Alliance a = mock(Alliance.class);
        lockAlliances(a, alliance("연합B"));

        service.cancelRequestedForAllianceExpiry(MATCH, A);

        verify(allianceMatchRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void cancelMatch_rejectsMissingOrNonpositiveIdBeforeAccessingRepositories() {
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.cancelMatch(USER, null));
        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.cancelMatch(USER, 0L));
        verifyNoInteractions(allianceRepository, allianceMatchRepository, userRepository);
    }

    @Test
    void cancelMatch_allowsMemberOfAllianceAWithoutCheckingB() {
        AllianceMatch match = requested();
        activeUser(100L);
        pairAndLockedMatch(match);
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                A, AllianceUniversityStatus.ACCEPTED)).thenReturn(aMembers());

        service.cancelMatch(USER, MATCH);

        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        verify(allianceUniversityRepository, never()).findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                B, AllianceUniversityStatus.ACCEPTED);
    }

    @Test
    void startScheduledMatch_rejectsMismatchedFirstAllianceUnderLock() {
        pairLookup();
        lockAlliances(activeAlliance("연합A"), activeAlliance("연합B"));
        AllianceMatch differentPair = AllianceMatch.request(999L, B, 6,
                OffsetDateTime.now().plusHours(10));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(differentPair));

        assertError(ErrorCode.COMMON_INVALID_INPUT, () -> service.startScheduledMatch(MATCH));

        verifyNoInteractions(allianceUniversityRepository);
    }

    // ===== JaCoCo 소스 화면에 남은 8개 분기만 정확히 보완 =====

    @Test
    void requestMatch_rejectsInvalidUserIdBeforeRepositoryAccess() {
        assertError(ErrorCode.COMMON_INVALID_INPUT,
                () -> service.requestMatch(null, A, B, 6));
        assertError(ErrorCode.COMMON_INVALID_INPUT,
                () -> service.requestMatch(0L, A, B, 6));
        assertError(ErrorCode.COMMON_INVALID_INPUT,
                () -> service.requestMatch(-1L, A, B, 6));

        verifyNoInteractions(userRepository, allianceRepository,
                allianceUniversityRepository, allianceMatchRepository);
    }

    @Test
    void startScheduledMatch_skipsSoftDeletedScheduledMatch() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(match));

        service.startScheduledMatch(MATCH);

        verifyNoInteractions(allianceUniversityRepository);
        verify(match, never()).cancelScheduled();
        verify(match, never()).startWithRoster(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void cancelMatch_rejectsSoftDeletedRequestedMatch() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getStatus()).thenReturn(AllianceMatchStatus.REQUESTED);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        pairLookup();
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(match));

        assertError(ErrorCode.MATCH_NOT_PENDING, () -> service.cancelMatch(USER, MATCH));

        verifyNoInteractions(allianceUniversityRepository);
        verify(match, never()).cancelRequested();
    }

    @Test
    void cancelExpiredRequest_ignoresSoftDeletedRequestedMatch() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getStatus()).thenReturn(AllianceMatchStatus.REQUESTED);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        pairAndLockedMatch(match);

        service.cancelExpiredRequest(MATCH);

        verify(match, never()).cancelRequested();
        verify(match, never()).getRequestExpiredAt();
    }

    @Test
    void cancelExpiredRequest_ignoresRequestedMatchWithoutDeadline() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getAllianceAId()).thenReturn(A);
        when(match.getAllianceBId()).thenReturn(B);
        when(match.getStatus()).thenReturn(AllianceMatchStatus.REQUESTED);
        // getRequestExpiredAt()은 mock 기본값인 null을 반환한다.
        pairAndLockedMatch(match);

        service.cancelExpiredRequest(MATCH);

        verify(match).getRequestExpiredAt();
        verify(match, never()).cancelRequested();
    }

    @Test
    void cancelRequestedForAllianceExpiry_ignoresSoftDeletedMatch() {
        AllianceMatch match = mock(AllianceMatch.class);
        when(match.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        pairLookup();
        lockAlliances(Alliance.create("연합A", 6, OffsetDateTime.now().minusHours(7)),
                alliance("연합B"));
        when(allianceMatchRepository.findByIdForUpdate(MATCH))
                .thenReturn(Optional.of(match));

        service.cancelRequestedForAllianceExpiry(MATCH, A);

        verify(match, never()).cancelRequested();
        verify(match, never()).getStatus();
    }

    @Test
    void requestMatch_rejectsIncompleteMemberCountOnFirstAlliance() {
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        lockAlliances(alliance("연합A"), alliance("연합B"));
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                A, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(aMembers().get(0)));
        when(allianceUniversityRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                B, AllianceUniversityStatus.ACCEPTED))
                .thenReturn(bMembers());

        assertError(ErrorCode.MATCH_MEMBERS_INCOMPLETE,
                () -> service.requestMatch(USER, A, B, 6));

        verify(allianceMatchRepository, never()).save(any());
    }

}
