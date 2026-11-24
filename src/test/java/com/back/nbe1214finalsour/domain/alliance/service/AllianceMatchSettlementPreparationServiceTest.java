package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementPreparationServiceTest {
    @Mock AllianceRepository allianceRepository;
    @Mock AllianceMatchRepository matchRepository;
    @Mock AllianceUniversityRepository memberRepository;
    @Mock AllianceMatchSettlementRepository settlementRepository;
    @InjectMocks AllianceMatchSettlementPreparationService service;
    private final AllianceMatchSettlementSnapshot snapshot = new AllianceMatchSettlementSnapshot(
            300L,200L,11L,12L,21L,22L,120L,180L,80L,120L);

    private void givenMatch() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(7);
        Alliance a = Alliance.create("A",72,start.minusHours(1));
        Alliance b = Alliance.create("B",72,start.minusHours(1));
        a.startMatch(start);
        b.startMatch(start);
        AllianceMatch match = AllianceMatch.request(1L,2L,6,start.plusHours(1));
        match.accept(start);
        when(matchRepository.findPairById(99L)).thenReturn(Optional.of(new AllianceMatchPair(1L,2L)));
        when(allianceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(a));
        when(allianceRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(b));
        when(matchRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(match));
    }

    @Test
    @DisplayName("대결 참가 학교가 일치하면 PENDING 스냅샷 생성")
    void prepare_acceptsConfirmedSnapshot() {
        givenMatch();
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L,AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(AllianceUniversity.createFounder(1L,11L,101L),
                        AllianceUniversity.createFounder(1L,12L,102L)));
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                2L,AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(AllianceUniversity.createFounder(2L,21L,103L),
                        AllianceUniversity.createFounder(2L,22L,104L)));
        when(settlementRepository.saveAndFlush(any(AllianceMatchSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var saved = service.prepare(99L,snapshot);
        assertThat(saved.getStatus()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
        assertThat(saved.getBUniversity2Id()).isEqualTo(22L);
    }

    @Test
    @DisplayName("실제 연합 소속 학교와 다르면 지급 준비를 거부")
    void prepare_rejectsUnknownSchool() {
        givenMatch();
        when(memberRepository.findByAllianceIdAndStatusAndDeletedAtIsNullOrderByIdAsc(
                1L,AllianceUniversityStatus.ACCEPTED))
                .thenReturn(List.of(AllianceUniversity.createFounder(1L,11L,101L),
                        AllianceUniversity.createFounder(1L,13L,102L)));
        assertThatThrownBy(() -> service.prepare(99L,snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("참가 학교");
        verify(settlementRepository,never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("기존 스냅샷과 점수가 다르면 변경 불가")
    void prepare_rejectsModifiedSnapshot() {
        givenMatch();
        AllianceMatchSettlement existing = AllianceMatchSettlement.prepare(99L,300L,200L,
                11L,12L,21L,22L,120L,180L,80L,120L);
        when(settlementRepository.findById(99L)).thenReturn(Optional.of(existing));
        var changed = new AllianceMatchSettlementSnapshot(301L,200L,
                11L,12L,21L,22L,120L,180L,80L,120L);
        assertThatThrownBy(() -> service.prepare(99L,changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스냅샷");
    }
}
