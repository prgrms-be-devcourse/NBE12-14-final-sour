package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceExpirationSchedulerTest {

    @Mock
    private AllianceRepository allianceRepository;

    @Mock
    private AllianceMatchRepository matchRepository;

    @Mock
    private AllianceMatchService matchService;

    @Mock
    private AllianceExpirationService expirationService;

    @InjectMocks
    private AllianceExpirationScheduler scheduler;

    @Test
    @DisplayName("만료 대상 연합이 없으면 대결 취소나 만료 처리를 하지 않는다")
    void noDueAlliancesDoesNothing() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of());

        scheduler.expireDueAlliances();

        verify(allianceRepository).findDueExpirationIds(any(OffsetDateTime.class));
        verifyNoInteractions(matchRepository, matchService, expirationService);
    }

    @Test
    @DisplayName("신청 대결이 없는 연합은 바로 만료 처리를 시도한다")
    void expiresAllianceWithoutRequestedMatches() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenReturn(List.of());

        scheduler.expireDueAlliances();

        InOrder order = inOrder(matchRepository, expirationService);
        order.verify(matchRepository).findRequestedIdsForAlliance(10L);
        order.verify(expirationService).expireIfDue(10L);
        verifyNoInteractions(matchService);
    }

    @Test
    @DisplayName("신청 대결을 모두 취소한 후에 연합 만료를 시도한다")
    void cancelsRequestedMatchesBeforeExpiringAlliance() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenReturn(List.of(101L, 102L));

        scheduler.expireDueAlliances();

        InOrder order = inOrder(matchRepository, matchService, expirationService);
        order.verify(matchRepository).findRequestedIdsForAlliance(10L);
        order.verify(matchService).cancelRequestedForAllianceExpiry(101L, 10L);
        order.verify(matchService).cancelRequestedForAllianceExpiry(102L, 10L);
        order.verify(expirationService).expireIfDue(10L);
    }

    @Test
    @DisplayName("여러 연합의 신청 대결을 각각 정리한 후 만료를 시도한다")
    void handlesMultipleDueAlliances() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenReturn(List.of(101L));
        when(matchRepository.findRequestedIdsForAlliance(20L))
                .thenReturn(List.of(201L));

        scheduler.expireDueAlliances();

        InOrder order = inOrder(matchRepository, matchService, expirationService);
        order.verify(matchRepository).findRequestedIdsForAlliance(10L);
        order.verify(matchService).cancelRequestedForAllianceExpiry(101L, 10L);
        order.verify(expirationService).expireIfDue(10L);
        order.verify(matchRepository).findRequestedIdsForAlliance(20L);
        order.verify(matchService).cancelRequestedForAllianceExpiry(201L, 20L);
        order.verify(expirationService).expireIfDue(20L);
    }

    @Test
    @DisplayName("한 연합의 신청 대결 조회 실패가 다른 연합의 만료를 막지 않는다")
    void lookupFailureDoesNotStopNextAlliance() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenThrow(new IllegalStateException("대결 조회 실패"));
        when(matchRepository.findRequestedIdsForAlliance(20L))
                .thenReturn(List.of());

        scheduler.expireDueAlliances();

        verify(expirationService, never()).expireIfDue(10L);
        verify(expirationService).expireIfDue(20L);
        verifyNoInteractions(matchService);
    }

    @Test
    @DisplayName("대결 취소 중 실패하면 해당 연합 만료를 중단하고 다음 연합을 처리한다")
    void cancelFailureDoesNotStopNextAlliance() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenReturn(List.of(101L, 102L));
        when(matchRepository.findRequestedIdsForAlliance(20L))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("대결 취소 실패"))
                .when(matchService).cancelRequestedForAllianceExpiry(101L, 10L);

        scheduler.expireDueAlliances();

        verify(matchService).cancelRequestedForAllianceExpiry(101L, 10L);
        verify(matchService, never()).cancelRequestedForAllianceExpiry(102L, 10L);
        verify(expirationService, never()).expireIfDue(10L);
        verify(expirationService).expireIfDue(20L);
    }

    @Test
    @DisplayName("연합 만료 처리 중 실패해도 다음 연합을 계속 처리한다")
    void expirationFailureDoesNotStopNextAlliance() {
        when(allianceRepository.findDueExpirationIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));
        when(matchRepository.findRequestedIdsForAlliance(10L))
                .thenReturn(List.of());
        when(matchRepository.findRequestedIdsForAlliance(20L))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("연합 만료 실패"))
                .when(expirationService).expireIfDue(10L);

        scheduler.expireDueAlliances();

        InOrder order = inOrder(expirationService);
        order.verify(expirationService).expireIfDue(10L);
        order.verify(expirationService).expireIfDue(20L);
        verifyNoInteractions(matchService);
    }
}
