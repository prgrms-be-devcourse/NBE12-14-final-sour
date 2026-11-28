package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.university.repository.UniversityRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus.ACCEPTED;
import static com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus.PENDING;
import static com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllianceInvitationServiceTest {
    private static final long ALLIANCE_ID = 1L;
    private static final long FOUNDER_SCHOOL_ID = 2L;
    private static final long INVITED_SCHOOL_ID = 3L;
    private static final long FOUNDER_ID = 4L;
    private static final long RECIPIENT_ID = 5L;
    private static final long INVITATION_ID = 1001L;
    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2020-01-01T00:00:00Z");

    @Mock private AllianceRepository alliances;
    @Mock private AllianceUniversityRepository memberships;
    @Mock private UserRepository users;
    @Mock private UniversityRepository universities;

    @BeforeEach
    void setUpAlliance() {
        when(alliances.findByIdForUpdate(ALLIANCE_ID))
                .thenReturn(Optional.of(Alliance.create("테스트 연합", 6, EXPIRES_AT.minusHours(6))));
    }

    @Test
    void approvalAtCapacityRejectsOnlyOtherPendingMemberships() {
        givenActiveUser(RECIPIENT_ID, INVITED_SCHOOL_ID);
        givenFoundingMember();
        var invitation = withId(INVITATION_ID, AllianceUniversity.createInvitation(ALLIANCE_ID, INVITED_SCHOOL_ID, FOUNDER_ID));
        var sameRow = withId(INVITATION_ID, AllianceUniversity.createInvitation(ALLIANCE_ID, INVITED_SCHOOL_ID, FOUNDER_ID));
        var otherPending = withId(1002L, AllianceUniversity.createApplication(ALLIANCE_ID, 6L, 7L));
        var alreadyRejected = withId(1003L, AllianceUniversity.createApplication(ALLIANCE_ID, 8L, 9L));
        alreadyRejected.reject();
        when(memberships.findAllianceIdByApplicationId(INVITATION_ID)).thenReturn(Optional.of(ALLIANCE_ID));
        when(memberships.findById(INVITATION_ID)).thenReturn(Optional.of(invitation));
        // A defensive check: a separate instance may represent the same row in the input list.
        when(memberships.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(ALLIANCE_ID, PENDING))
                .thenReturn(List.of(sameRow, otherPending, alreadyRejected));

        var approved = serviceAt(EXPIRES_AT.minusNanos(1)).approve(RECIPIENT_ID, INVITATION_ID);

        assertThat(approved.getStatus()).isEqualTo(ACCEPTED);
        assertThat(sameRow.getStatus()).isEqualTo(PENDING);
        assertThat(otherPending.getStatus()).isEqualTo(REJECTED);
        assertThat(alreadyRejected.getStatus()).isEqualTo(REJECTED);
    }

    @Test
    void inviteAllowsSendingJustBeforeExpiration() {
        givenActiveUser(FOUNDER_ID, FOUNDER_SCHOOL_ID);
        givenFoundingMember();
        when(universities.existsById(INVITED_SCHOOL_ID)).thenReturn(true);
        when(memberships.save(any(AllianceUniversity.class))).thenAnswer(call -> call.getArgument(0));

        var invitation = serviceAt(EXPIRES_AT.minusNanos(1)).invite(FOUNDER_ID, ALLIANCE_ID, INVITED_SCHOOL_ID);

        assertThat(invitation.getStatus()).isEqualTo(PENDING);
        assertThat(invitation.getUniversityId()).isEqualTo(INVITED_SCHOOL_ID);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void inviteRejectsSendingAtOrAfterExpiration(long nanosAfterExpiration) {
        User founder = mock(User.class);
        when(users.findById(FOUNDER_ID)).thenReturn(Optional.of(founder));
        when(founder.getStatus()).thenReturn(UserStatus.ACTIVE);

        assertThatThrownBy(() -> serviceAt(EXPIRES_AT.plusNanos(nanosAfterExpiration))
                .invite(FOUNDER_ID, ALLIANCE_ID, INVITED_SCHOOL_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ALLY_NOT_ACTIVE));
        verifyNoInteractions(memberships);
    }

    private AllianceInvitationService serviceAt(OffsetDateTime time) {
        return new AllianceInvitationService(alliances, memberships, users, universities,
                Clock.fixed(time.toInstant(), ZoneOffset.UTC));
    }

    private void givenActiveUser(long userId, long schoolId) {
        User user = mock(User.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(schoolId);
    }

    private void givenFoundingMember() {
        when(memberships.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(ALLIANCE_ID, ACCEPTED))
                .thenReturn(List.of(AllianceUniversity.createFounder(ALLIANCE_ID, FOUNDER_SCHOOL_ID, FOUNDER_ID)));
    }

    private static AllianceUniversity withId(long id, AllianceUniversity membership) {
        ReflectionTestUtils.setField(membership, "id", id);
        return membership;
    }
}
