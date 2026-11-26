package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import com.back.nbe1214finalsour.domain.chat.entity.AllianceChatRoom;
import com.back.nbe1214finalsour.domain.chat.repository.AllianceChatRoomRepository;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceServiceTest {

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceChatRoomRepository allianceChatRoomRepository;

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AllianceService allianceService;

    // 1. 정상적인 연합 생성
    @Test
    @DisplayName("연합 생성 시 최초 학교가 자동으로 가입된다")
    void createAlliance_addsFounderUniversity() {

        // given
        Long userId = 1L;
        Long universityId = 10L;
        Long allianceId = 100L;

        User user = mock(User.class);
        when(user.getUniversityId()).thenReturn(universityId);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        Alliance savedAlliance = mock(Alliance.class);
        when(savedAlliance.getId()).thenReturn(allianceId);
        when(savedAlliance.getName()).thenReturn("저장된연합");

        when(allianceRepository.save(any(Alliance.class)))
                .thenReturn(savedAlliance);

        // when
        Alliance result = allianceService.createAlliance(
                userId,
                "테스트연합",
                24
        );

        // then
        assertThat(result).isSameAs(savedAlliance);
        ArgumentCaptor<AllianceChatRoom> roomCaptor = ArgumentCaptor.forClass(AllianceChatRoom.class);
        verify(allianceChatRoomRepository).save(roomCaptor.capture());
        assertThat(roomCaptor.getValue().getId()).isEqualTo(allianceId);
        assertThat(roomCaptor.getValue().getAllianceName()).isEqualTo("저장된연합");

        ArgumentCaptor<Alliance> allianceCaptor =
                ArgumentCaptor.forClass(Alliance.class);

        verify(allianceRepository).save(allianceCaptor.capture());

        Alliance createdAlliance = allianceCaptor.getValue();

        assertThat(createdAlliance.getName()).isEqualTo("테스트연합");
        assertThat(createdAlliance.getDurationHours()).isEqualTo(24);

        ArgumentCaptor<AllianceUniversity> memberCaptor =
                ArgumentCaptor.forClass(AllianceUniversity.class);

        verify(allianceUniversityRepository)
                .save(memberCaptor.capture());

        AllianceUniversity founder = memberCaptor.getValue();

        assertThat(founder.getAllianceId()).isEqualTo(allianceId);
        assertThat(founder.getUniversityId()).isEqualTo(universityId);
        assertThat(founder.getApplicantUserId()).isEqualTo(userId);
        assertThat(founder.getApproverUserId()).isEqualTo(userId);
        assertThat(founder.getStatus())
                .isEqualTo(AllianceUniversityStatus.ACCEPTED);
    }

    // 2. 이미 연합에 가입한 학교
    @Test
    @DisplayName("이미 가입한 학교는 다른 연합을 생성할 수 없다")
    void createAlliance_rejectsAlreadyJoinedUniversity() {

        // given
        Long userId = 1L;
        Long universityId = 10L;

        User user = mock(User.class);

        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(universityId);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        when(allianceUniversityRepository
                .existsByUniversityIdAndStatusAndDeletedAtIsNull(
                        universityId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() ->
                allianceService.createAlliance(userId, "새로운 연합", 24)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_UNIVERSITY_ALREADY_JOINED
                );

        verifyNoInteractions(allianceRepository);

        verify(allianceUniversityRepository, never())
                .save(any(AllianceUniversity.class));
    }

    // 3. 연합 유지 시간 범위 검증
    @Test
    @DisplayName("유지 시간이 6~72시간 범위를 벗어나면 연합 생성이 거부된다")
    void createAlliance_rejectsInvalidDuration() {

        // given
        Long userId = 1L;
        Long universityId = 10L;

        User user = mock(User.class);

        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(universityId);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // 5시간: 최소 시간 미만
        assertThatThrownBy(() ->
                allianceService.createAlliance(userId, "테스트연합", 5)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.COMMON_INVALID_INPUT
                );

        // 73시간: 최대 시간 초과
        assertThatThrownBy(() ->
                allianceService.createAlliance(userId, "테스트연합", 73)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.COMMON_INVALID_INPUT
                );

        verifyNoInteractions(allianceRepository);

        verify(allianceUniversityRepository, never())
                .save(any(AllianceUniversity.class));
    }

    // 4. 존재하지 않는 사용자
    @Test
    @DisplayName("존재하지 않는 사용자는 연합을 생성할 수 없다")
    void createAlliance_rejectsUnknownUser() {

        // given
        Long userId = 999L;

        when(userRepository.findById(userId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                allianceService.createAlliance(userId, "테스트연합", 24)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.USER_NOT_FOUND
                );

        verifyNoInteractions(allianceRepository);
        verifyNoInteractions(allianceUniversityRepository);
    }

    // 5. 탈퇴한 사용자
    @Test
    @DisplayName("탈퇴한 사용자는 연합을 생성할 수 없다")
    void createAlliance_rejectsWithdrawnUser() {

        // given
        Long userId = 1L;

        User user = mock(User.class);

        when(user.getStatus())
                .thenReturn(UserStatus.WITHDRAWN);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() ->
                allianceService.createAlliance(userId, "테스트연합", 24)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_USER_NOT_ACTIVE
                );

        verifyNoInteractions(allianceRepository);
        verifyNoInteractions(allianceUniversityRepository);
    }
}
