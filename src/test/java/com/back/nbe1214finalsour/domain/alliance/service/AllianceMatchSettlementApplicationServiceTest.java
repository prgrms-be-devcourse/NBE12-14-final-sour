package com.back.nbe1214finalsour.domain.alliance.service;

import com.back.nbe1214finalsour.domain.alliance.entity.*;
import com.back.nbe1214finalsour.domain.alliance.repository.AllianceMatchSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AllianceMatchSettlementApplicationServiceTest {
    @Mock AllianceMatchSettlementRepository repository;
    @Mock AllianceMatchRedisSettlementService redisService;
    @InjectMocks AllianceMatchSettlementApplicationService service;

    private AllianceMatchSettlement record;
    @BeforeEach
    void setup() {
        record = AllianceMatchSettlement.prepare(99L,300L,200L,
                11L,12L,21L,22L,120L,180L,80L,120L);
    }

    @Test
    @DisplayName("A연합 승리면 B연합 학교별 기여도를 차감")
    void applyPending_allianceAWins() {
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));
        when(redisService.settleOnce(99L,21L,22L,11L,12L,80L,120L)).thenReturn(true);
        service.applyPending(99L);
        verify(redisService).settleOnce(99L,21L,22L,11L,12L,80L,120L);
        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("이미 Redis 적용된 경우 재지급 없이 APPLIED로 복구")
    void applyPending_retriesAfterRedisSuccess() {
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));
        when(redisService.settleOnce(99L,21L,22L,11L,12L,80L,120L)).thenReturn(false);
        service.applyPending(99L);
        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("Redis 실패하면 PENDING 유지")
    void applyPending_keepsPendingOnError() {
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));
        when(redisService.settleOnce(99L,21L,22L,11L,12L,80L,120L))
                .thenThrow(new IllegalStateException("Redis 실패"));
        assertThatThrownBy(() -> service.applyPending(99L))
                .hasMessage("Redis 실패");
        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.PENDING);
    }

    @Test
    @DisplayName("무승부는 Redis 지급하지 않음")
    void applyPending_draw() {
        AllianceMatchSettlement draw = AllianceMatchSettlement.prepare(99L,200L,200L,
                11L,12L,21L,22L,100L,100L,100L,100L);
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(draw));
        service.applyPending(99L);
        verifyNoInteractions(redisService);
        assertThat(draw.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }
}
