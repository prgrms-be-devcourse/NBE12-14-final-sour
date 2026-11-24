package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.Alliance;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversity;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceQueryServiceTest {

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    @InjectMocks
    private AllianceQueryService allianceQueryService;

    // 1. 정상적인 연합 조회
    @Test
    @DisplayName("연합 ID로 연합 정보를 조회한다")
    void getAlliance_returnsAlliance() {

        // given
        Long allianceId = 100L;

        Alliance alliance = Alliance.create(
                "테스트연합",
                24,
                OffsetDateTime.parse("2026-11-15T09:00:00+09:00")
        );

        when(allianceRepository.findById(allianceId))
                .thenReturn(Optional.of(alliance));

        // when
        Alliance result = allianceQueryService.getAlliance(allianceId);

        // then
        assertThat(result).isSameAs(alliance);
        assertThat(result.getName()).isEqualTo("테스트연합");
        assertThat(result.getDurationHours()).isEqualTo(24);

        verify(allianceRepository).findById(allianceId);
    }

    // 2. 존재하지 않는 연합 조회
    @Test
    @DisplayName("존재하지 않는 연합은 조회할 수 없다")
    void getAlliance_rejectsUnknownAlliance() {

        // given
        Long allianceId = 999L;

        when(allianceRepository.findById(allianceId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                allianceQueryService.getAlliance(allianceId)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_NOT_FOUND
                );

        verify(allianceRepository).findById(allianceId);
    }

    // 3. 삭제된 연합 조회
    @Test
    @DisplayName("삭제된 연합은 조회할 수 없다")
    void getAlliance_rejectsDeletedAlliance() {

        // given
        Long allianceId = 100L;

        Alliance deletedAlliance = mock(Alliance.class);

        when(deletedAlliance.getDeletedAt())
                .thenReturn(OffsetDateTime.parse(
                        "2026-11-15T18:00:00+09:00"
                ));

        when(allianceRepository.findById(allianceId))
                .thenReturn(Optional.of(deletedAlliance));

        // when & then
        assertThatThrownBy(() ->
                allianceQueryService.getAlliance(allianceId)
        )
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        ErrorCode.ALLY_NOT_FOUND
                );

        verify(allianceRepository).findById(allianceId);
    }

    // 4. 가입 승인된 학교 목록 조회
    @Test
    @DisplayName("연합에 가입 승인된 학교 목록을 조회한다")
    void getAcceptedUniversities_returnsMembers() {

        // given
        Long allianceId = 100L;

        Alliance alliance = Alliance.create(
                "테스트연합",
                24,
                OffsetDateTime.parse("2026-11-15T09:00:00+09:00")
        );

        AllianceUniversity first = mock(AllianceUniversity.class);
        AllianceUniversity second = mock(AllianceUniversity.class);

        when(allianceRepository.findById(allianceId))
                .thenReturn(Optional.of(alliance));

        when(allianceUniversityRepository
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                ))
                .thenReturn(List.of(first, second));

        // when
        List<AllianceUniversity> result =
                allianceQueryService.getAcceptedUniversities(allianceId);

        // then
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly(first, second);

        verify(allianceUniversityRepository)
                .findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                        allianceId,
                        AllianceUniversityStatus.ACCEPTED
                );
    }
}
