package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceExpirationServiceTest {

    private static final long ALLIANCE_ID = 100L;

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    @Mock
    private AllianceMatchRepository matchRepository;

    @InjectMocks
    private AllianceExpirationService expirationService;

    private static Alliance expiredTimeAlliance() {
        // 6시간 유지되는 연합을 8시간 전에 생성해 이미 만료 시각이 지나도록 한다.
        return Alliance.create("테스트연합", 6, OffsetDateTime.now().minusHours(8));
    }

    @Test
    @DisplayName("연합이 없으면 다른 저장소를 조회하지 않는다")
    void missingAllianceDoesNothing() {
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.empty());

        expirationService.expireIfDue(ALLIANCE_ID);

        verifyNoInteractions(matchRepository, allianceUniversityRepository);
    }

    @Test
    @DisplayName("삭제된 연합은 만료시키지 않는다")
    void deletedAllianceDoesNothing() {
        Alliance alliance = mock(Alliance.class);
        when(alliance.getDeletedAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        expirationService.expireIfDue(ALLIANCE_ID);

        verifyNoInteractions(matchRepository, allianceUniversityRepository);
    }

    @Test
    @DisplayName("이미 만료된 연합은 중복 처리하지 않는다")
    void alreadyExpiredAllianceDoesNothing() {
        Alliance alliance = expiredTimeAlliance();
        alliance.expire(OffsetDateTime.now());
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.EXPIRED);
        verifyNoInteractions(matchRepository, allianceUniversityRepository);
    }

    @Test
    @DisplayName("만료 시각이 없다면 만료시키지 않는다")
    void noExpirationTimeDoesNothing() {
        // 정상 생성 API로는 expiredAt이 null이 될 수 없어 해당 방어 분기만 mock으로 검증한다.
        Alliance alliance = mock(Alliance.class);
        when(alliance.getStatus()).thenReturn(AllianceStatus.ACTIVE);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        expirationService.expireIfDue(ALLIANCE_ID);

        verifyNoInteractions(matchRepository, allianceUniversityRepository);
    }

    @Test
    @DisplayName("아직 유지 시간이 남은 연합은 만료시키지 않는다")
    void futureExpirationDoesNothing() {
        Alliance alliance = Alliance.create("테스트연합", 6, OffsetDateTime.now());
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        verifyNoInteractions(matchRepository, allianceUniversityRepository);
    }

    @Test
    @DisplayName("만료 시간이 지났어도 열린 대결이 있으면 연합과 학교를 유지한다")
    void openMatchDefersExpiration() {
        Alliance alliance = expiredTimeAlliance();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));
        when(matchRepository.existsOpenMatchForAlliance(ALLIANCE_ID)).thenReturn(true);

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.ACTIVE);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    @DisplayName("대결 중 연합은 만료 시간이 지나도 대결이 열려 있으면 유지한다")
    void inMatchAllianceStaysInMatchWhileOpenMatchExists() {
        OffsetDateTime createdAt = OffsetDateTime.now().minusHours(8);
        Alliance alliance = Alliance.create("테스트연합", 6, createdAt);
        alliance.startMatch(createdAt.plusHours(1));
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));
        when(matchRepository.existsOpenMatchForAlliance(ALLIANCE_ID)).thenReturn(true);

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.IN_MATCH);
        verifyNoInteractions(allianceUniversityRepository);
    }

    @Test
    @DisplayName("기한이 지나고 미처리 대결이 없으면 연합 및 승인·대기 학교 상태를 정리한다")
    void expiredAllianceReleasesAcceptedAndRejectsPendingMembers() {
        Alliance alliance = expiredTimeAlliance();
        AllianceUniversity accepted = AllianceUniversity.createFounder(ALLIANCE_ID, 10L, 1L);
        AllianceUniversity pending = AllianceUniversity.createApplication(ALLIANCE_ID, 20L, 2L);
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));
        when(allianceUniversityRepository.findByAllianceIdAndDeletedAtIsNullOrderByIdAsc(ALLIANCE_ID))
                .thenReturn(List.of(accepted, pending));

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.EXPIRED);
        assertThat(accepted.getStatus()).isEqualTo(AllianceUniversityStatus.LEFT);
        assertThat(pending.getStatus()).isEqualTo(AllianceUniversityStatus.REJECTED);
        verify(matchRepository).existsOpenMatchForAlliance(ALLIANCE_ID);
        verify(allianceUniversityRepository).findByAllianceIdAndDeletedAtIsNullOrderByIdAsc(ALLIANCE_ID);
    }

    @Test
    @DisplayName("기한이 지난 연합에 학교가 없어도 정상 만료한다")
    void expiredAllianceWithNoMembersStillExpires() {
        Alliance alliance = expiredTimeAlliance();
        when(allianceRepository.findByIdForUpdate(ALLIANCE_ID)).thenReturn(Optional.of(alliance));
        when(allianceUniversityRepository.findByAllianceIdAndDeletedAtIsNullOrderByIdAsc(ALLIANCE_ID))
                .thenReturn(List.of());

        expirationService.expireIfDue(ALLIANCE_ID);

        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.EXPIRED);
        verify(matchRepository).existsOpenMatchForAlliance(ALLIANCE_ID);
    }
}
