package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.chat.repository.AllianceChatRoomRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AllianceInvitationIsolationTest {
    @Test
    void existingApplicationEndpointsCannotDecideAnInvitation() {
        var alliances = mock(AllianceRepository.class);
        var members = mock(AllianceUniversityRepository.class);
        var users = mock(UserRepository.class);
        var service = new AllianceService(alliances, mock(AllianceChatRoomRepository.class), members, users);
        var user = mock(User.class);
        var invitation = AllianceUniversity.createInvitation(1L, 2L, 3L);
        when(users.findById(4L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(members.findAllianceIdByApplicationId(5L)).thenReturn(Optional.of(1L));
        when(alliances.findByIdForUpdate(1L)).thenReturn(Optional.of(Alliance.create("연합", 6, OffsetDateTime.now())));
        when(members.findById(5L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.approveApplication(4L, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ALLY_APPLICATION_NOT_FOUND);
        assertThatThrownBy(() -> service.rejectApplication(4L, 5L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ALLY_APPLICATION_NOT_FOUND);
        assertThat(invitation.getStatus()).isEqualTo(AllianceUniversityStatus.PENDING);
    }
}
