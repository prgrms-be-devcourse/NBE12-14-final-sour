package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchStartSchedulerTest {
    @Mock AllianceMatchRepository repository;
    @Mock AllianceMatchService service;
    @InjectMocks AllianceMatchStartScheduler scheduler;

    @Test
    void startsDueMatchesIndividually() {
        when(repository.findDueScheduledIds(any(OffsetDateTime.class))).thenReturn(List.of(10L, 20L));
        scheduler.startDueMatches();
        verify(service).startScheduledMatch(10L);
        verify(service).startScheduledMatch(20L);
    }

    @Test
    void failureDoesNotBlockFollowingMatches() {
        when(repository.findDueScheduledIds(any(OffsetDateTime.class))).thenReturn(List.of(10L, 20L));
        doThrow(new IllegalStateException("테스트 실패")).when(service).startScheduledMatch(10L);
        scheduler.startDueMatches();
        verify(service).startScheduledMatch(20L);
    }
}
