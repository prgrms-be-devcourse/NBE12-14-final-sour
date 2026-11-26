package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.alliance.entity.AllianceUniversityStatus;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceUniversityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AllianceAllyCheckerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private AllianceUniversityRepository allianceUniversityRepository;

    private AllianceAllyChecker allianceAllyChecker;

    @BeforeEach
    void setUp() {
        allianceAllyChecker = new AllianceAllyChecker(allianceUniversityRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("내 학교 ID가 null이면 동맹이 아니며 DB를 조회하지 않는다")
    void areAllies_firstUniversityNull() {
        assertThat(allianceAllyChecker.areAllies(null, 20L)).isFalse();
        then(allianceUniversityRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상대 학교 ID가 null이면 동맹이 아니며 DB를 조회하지 않는다")
    void areAllies_otherUniversityNull() {
        assertThat(allianceAllyChecker.areAllies(10L, null)).isFalse();
        then(allianceUniversityRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("같은 학교는 동맹으로 취급하지 않으며 DB를 조회하지 않는다")
    void areAllies_sameUniversity() {
        assertThat(allianceAllyChecker.areAllies(10L, 10L)).isFalse();
        then(allianceUniversityRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("서로 다른 두 학교가 같은 활성 연합에 있으면 true를 반환한다")
    void areAllies_currentAllyPairExists() {
        OffsetDateTime now = OffsetDateTime.now(FIXED_CLOCK);
        given(allianceUniversityRepository.existsCurrentAllyPair(
                10L, 20L, now, AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED))
                .willReturn(true);

        assertThat(allianceAllyChecker.areAllies(10L, 20L)).isTrue();
        then(allianceUniversityRepository).should().existsCurrentAllyPair(
                10L, 20L, now, AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED);
    }

    @Test
    @DisplayName("서로 다른 두 학교가 현재 동맹이 아니면 false를 반환한다")
    void areAllies_currentAllyPairDoesNotExist() {
        OffsetDateTime now = OffsetDateTime.now(FIXED_CLOCK);
        given(allianceUniversityRepository.existsCurrentAllyPair(
                10L, 20L, now, AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED))
                .willReturn(false);

        assertThat(allianceAllyChecker.areAllies(10L, 20L)).isFalse();
        then(allianceUniversityRepository).should().existsCurrentAllyPair(
                10L, 20L, now, AllianceUniversityStatus.ACCEPTED, AllianceStatus.EXPIRED);
    }
}
