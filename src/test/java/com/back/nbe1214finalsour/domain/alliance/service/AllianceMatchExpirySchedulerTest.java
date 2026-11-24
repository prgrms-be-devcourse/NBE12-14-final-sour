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
class AllianceMatchExpirySchedulerTest {
    @Mock AllianceMatchRepository repository;
    @Mock AllianceMatchService service;
    @InjectMocks AllianceMatchExpiryScheduler scheduler;

    @Test
    void expiredRequestIdsAreProcessedIndividually() {
        when(repository.findExpiredRequestIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));

        scheduler.cancelExpiredRequests();

        verify(service).cancelExpiredRequest(10L);
        verify(service).cancelExpiredRequest(20L);
    }

    @Test
    void oneFailureDoesNotSkipTheNextRequest() {
        when(repository.findExpiredRequestIds(any(OffsetDateTime.class)))
                .thenReturn(List.of(10L, 20L));
        doThrow(new IllegalStateException("테스트 오류"))
                .when(service).cancelExpiredRequest(10L);

        scheduler.cancelExpiredRequests();

        verify(service).cancelExpiredRequest(20L);
    }
}
