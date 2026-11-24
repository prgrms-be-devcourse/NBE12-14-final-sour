package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import com.back.nbe1214finalsour.domain.user.entity.User;
import com.back.nbe1214finalsour.domain.user.entity.UserStatus;
import com.back.nbe1214finalsour.domain.user.repository.UserRepository;
import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class AllianceMatchServiceTest {

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    @Mock
    private AllianceMatchRepository allianceMatchRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AllianceMatchService allianceMatchService;

    @Test
    @DisplayName("정상적인 대결 신청은 REQUESTED 상태로 저장된다")
    void requestMatch_success() {
        // given: 신청 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(100L);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 두 연합
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus()).thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));
        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 연합마다 학교 2개 가입 완료
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L),
                        AllianceUniversity.createFounder(20L, 201L, 4L)
                ));

        // given: 저장된 대결 객체를 반환하도록 설정
        when(allianceMatchRepository.save(any(AllianceMatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // when: 대결 신청
        AllianceMatch result = allianceMatchService.requestMatch(
                userId, allianceAId, allianceBId, 6
        );

        // then: 대결 정보 검증
        assertThat(result.getAllianceAId()).isEqualTo(10L);
        assertThat(result.getAllianceBId()).isEqualTo(20L);
        assertThat(result.getDurationHours()).isEqualTo(6);
        assertThat(result.getStatus())
                .isEqualTo(AllianceMatchStatus.REQUESTED);

        assertThat(result.getRequestExpiredAt())
                .isAfter(now.plusHours(23));
        assertThat(result.getRequestExpiredAt())
                .isBefore(now.plusHours(25));

        assertThat(result.getStartedAt()).isNull();
        assertThat(result.getEndedAt()).isNull();

        verify(allianceMatchRepository)
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("이미 대결 신청이 존재하면 중복 신청할 수 없다")
    void requestMatch_alreadyRequested() {
        // given: 신청 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(100L);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 정상적으로 활성화된 두 연합
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus()).thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));
        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 양쪽 연합 모두 2개 학교 가입 완료
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L),
                        AllianceUniversity.createFounder(20L, 201L, 4L)
                ));

        // given: 이미 대기 중인 대결 신청이 존재함
        when(allianceMatchRepository.existsRequestedBetween(
                allianceAId, allianceBId
        )).thenReturn(true);

        // when & then: 중복 신청 시 예외 발생
        assertThatThrownBy(() ->
                allianceMatchService.requestMatch(
                        userId, allianceAId, allianceBId, 6
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.MATCH_ALREADY_REQUESTED)
        );

        // 중복 신청은 DB에 저장하지 않음
        verify(allianceMatchRepository, never())
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("연합 A 소속 학교 회원이 아니면 대결을 신청할 수 없다")
    void requestMatch_notMember() {
        // given: 연합 A에 속하지 않은 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(999L);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 정상적으로 활성화된 두 연합
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus()).thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));
        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 양쪽 연합 모두 2개 학교로 구성
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 2L),
                        AllianceUniversity.createFounder(10L, 101L, 3L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 4L),
                        AllianceUniversity.createFounder(20L, 201L, 5L)
                ));

        // when & then: 소속 회원이 아니면 예외 발생
        assertThatThrownBy(() ->
                allianceMatchService.requestMatch(
                        userId, allianceAId, allianceBId, 6
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.ALLY_NOT_MEMBER)
        );

        // 권한 검증에 실패했으므로 DB 저장은 하지 않음
        verify(allianceMatchRepository, never())
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("상대 연합의 학교가 2개 미만이면 대결을 신청할 수 없다")
    void requestMatch_notEnoughMembers() {
        // given: 정상 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 정상적으로 활성화된 두 연합
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus()).thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));
        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 연합 A는 학교 2개
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        // given: 연합 B는 학교 1개
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L)
                ));

        // when & then: 정원 미충족으로 신청 거절
        assertThatThrownBy(() ->
                allianceMatchService.requestMatch(
                        userId, allianceAId, allianceBId, 6
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.MATCH_MEMBERS_INCOMPLETE)
        );

        // 대결 신청 정보는 저장하지 않음
        verify(allianceMatchRepository, never())
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("만료된 연합은 대결을 신청할 수 없다")
    void requestMatch_expiredAlliance() {
        // given: 정상 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 연합 A는 이미 만료됨
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus())
                .thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.minusHours(1));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));

        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // when & then: 만료된 연합이므로 신청 거절
        assertThatThrownBy(() ->
                allianceMatchService.requestMatch(
                        userId, allianceAId, allianceBId, 6
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.ALLY_NOT_ACTIVE)
        );

        // DB에 대결 신청을 저장하지 않음
        verify(allianceMatchRepository, never())
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("연합 만료 전 대결을 끝낼 수 없으면 신청을 거절한다")
    void requestMatch_notEnoughTime() {
        // given: 정상 회원
        Long userId = 1L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(100L);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 연합 A는 5시간 뒤 만료
        OffsetDateTime now = OffsetDateTime.now();

        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus()).thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(5));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));
        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 양쪽 연합 모두 학교 2개 가입
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId, AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L),
                        AllianceUniversity.createFounder(20L, 201L, 4L)
                ));

        // when & then: 6시간 대결 신청은 불가능
        assertThatThrownBy(() ->
                allianceMatchService.requestMatch(
                        userId, allianceAId, allianceBId, 6
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.MATCH_INSUFFICIENT_TIME)
        );

        // 신청 정보가 저장되지 않았는지 검증
        verify(allianceMatchRepository, never())
                .save(any(AllianceMatch.class));
    }

    @Test
    @DisplayName("상대 연합 회원이 대결을 수락하면 대결이 시작된다")
    void acceptMatch_success() {
        // given: 연합 A가 신청한 6시간 대결
        Long matchId = 50L;
        Long userId = 3L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        OffsetDateTime now = OffsetDateTime.now();

        AllianceMatch match = AllianceMatch.request(
                allianceAId,
                allianceBId,
                6,
                now.plusHours(12)
        );

        when(allianceMatchRepository.findPairById(matchId))
                .thenReturn(Optional.of(new AllianceMatchPair(allianceAId, allianceBId)));

        when(allianceMatchRepository.findByIdForUpdate(matchId))
                .thenReturn(Optional.of(match));

        // given: 연합 B 소속 정상 회원
        User user = mock(User.class);

        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(200L);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 양쪽 연합 모두 활성 상태
        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus())
                .thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus())
                .thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));

        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 양쪽 연합 모두 학교 2개
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L),
                        AllianceUniversity.createFounder(20L, 201L, 4L)
                ));

        // when: 연합 B 회원이 대결 수락
        OffsetDateTime before = OffsetDateTime.now();

        AllianceMatch result =
                allianceMatchService.acceptMatch(userId, matchId);

        OffsetDateTime after = OffsetDateTime.now();

        // then: 대결 진행 상태 확인
        assertThat(result.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        // 수락 시점에 대결 시작
        assertThat(result.getStartedAt())
                .isBetween(before, after);

        // 종료 시각 = 시작 시각 + 6시간
        assertThat(result.getEndedAt())
                .isEqualTo(result.getStartedAt().plusHours(6));
    }

    @Test
    @DisplayName("상대 연합 소속 회원이 아니면 대결을 수락할 수 없다")
    void acceptMatch_notMember() {
        // given: 연합 A가 신청한 대결
        Long matchId = 50L;
        Long userId = 3L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        OffsetDateTime now = OffsetDateTime.now();

        AllianceMatch match = AllianceMatch.request(
                allianceAId,
                allianceBId,
                6,
                now.plusHours(12)
        );

        when(allianceMatchRepository.findPairById(matchId))
                .thenReturn(Optional.of(new AllianceMatchPair(allianceAId, allianceBId)));

        when(allianceMatchRepository.findByIdForUpdate(matchId))
                .thenReturn(Optional.of(match));

        // given: 연합 B에 소속되지 않은 회원
        User user = mock(User.class);

        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getUniversityId()).thenReturn(999L);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 두 연합 모두 활성 상태
        Alliance allianceA = mock(Alliance.class);
        Alliance allianceB = mock(Alliance.class);

        when(allianceA.getStatus())
                .thenReturn(AllianceStatus.ACTIVE);
        when(allianceB.getStatus())
                .thenReturn(AllianceStatus.ACTIVE);

        when(allianceA.getExpiredAt())
                .thenReturn(now.plusHours(48));
        when(allianceB.getExpiredAt())
                .thenReturn(now.plusHours(48));

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));

        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // given: 양쪽 연합 모두 2개 학교 가입
        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceAId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(10L, 100L, 1L),
                        AllianceUniversity.createFounder(10L, 101L, 2L)
                ));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceBId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(
                        AllianceUniversity.createFounder(20L, 200L, 3L),
                        AllianceUniversity.createFounder(20L, 201L, 4L)
                ));

        // when & then: 수락 권한이 없으면 예외 발생
        assertThatThrownBy(() ->
                allianceMatchService.acceptMatch(userId, matchId)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.ALLY_NOT_MEMBER)
        );

        // 대결 상태는 변경되지 않아야 함
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.REQUESTED);

        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();

        // 연합도 대결 중 상태로 변경하면 안 됨
        verify(allianceA, never())
                .startMatch(any(OffsetDateTime.class));

        verify(allianceB, never())
                .startMatch(any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("이미 진행 중인 대결은 다시 수락할 수 없다")
    void acceptMatch_alreadyInProgress() {
        // given: 이미 시작된 대결
        Long matchId = 50L;
        Long userId = 3L;
        Long allianceAId = 10L;
        Long allianceBId = 20L;

        OffsetDateTime now = OffsetDateTime.now();

        AllianceMatch match = AllianceMatch.request(
                allianceAId,
                allianceBId,
                6,
                now.plusHours(12)
        );

        // 대결을 이미 시작한 상태로 설정
        OffsetDateTime startedAt = now.minusMinutes(1);
        match.accept(startedAt);

        when(allianceMatchRepository.findPairById(matchId))
                .thenReturn(Optional.of(new AllianceMatchPair(allianceAId, allianceBId)));

        when(allianceMatchRepository.findByIdForUpdate(matchId))
                .thenReturn(Optional.of(match));

        // given: 정상 회원
        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));

        // given: 두 연합도 이미 IN_MATCH 상태
        Alliance allianceA = Alliance.create(
                "테스트연합A", 48, now.minusHours(1)
        );

        Alliance allianceB = Alliance.create(
                "테스트연합B", 48, now.minusHours(1)
        );

        allianceA.startMatch(startedAt);
        allianceB.startMatch(startedAt);

        when(allianceRepository.findByIdForUpdate(allianceAId))
                .thenReturn(Optional.of(allianceA));

        when(allianceRepository.findByIdForUpdate(allianceBId))
                .thenReturn(Optional.of(allianceB));

        // when & then: 중복 수락은 거절
        assertThatThrownBy(() ->
                allianceMatchService.acceptMatch(userId, matchId)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(ErrorCode.MATCH_NOT_PENDING)
        );

        // 기존 대결 상태와 시작 시간이 유지되어야 함
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        assertThat(match.getStartedAt())
                .isEqualTo(startedAt);

        assertThat(match.getEndedAt())
                .isEqualTo(startedAt.plusHours(6));
    }
}