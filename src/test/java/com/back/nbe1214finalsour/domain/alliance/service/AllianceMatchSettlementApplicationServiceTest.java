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
import static org.mockito.BDDMockito.then;

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
        then(redisService).shouldHaveNoInteractions();
        assertThat(draw.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("대결 ID가 null이면 조회 없이 거부")
    void applyPending_rejectsNullMatchId() {
        assertThatThrownBy(() -> service.applyPending(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대결 ID가 올바르지 않습니다.");

        then(repository).shouldHaveNoInteractions();
        then(redisService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("대결 ID가 0 이하이면 조회 없이 거부")
    void applyPending_rejectsNonPositiveMatchIds() {
        for (long invalidMatchId : new long[] {0L, -1L}) {
            assertThatThrownBy(() -> service.applyPending(invalidMatchId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("대결 ID가 올바르지 않습니다.");
        }

        then(repository).shouldHaveNoInteractions();
        then(redisService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정산 이력이 없으면 예외 발생 및 Redis 호출 안 함")
    void applyPending_failsWhenSettlementNotFound() {
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyPending(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산 이력을 찾을 수 없습니다.");

        then(redisService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 APPLIED인 정산은 다시 Redis에 반영하지 않음")
    void applyPending_skipsAlreadyAppliedSettlement() {
        record.markApplied();
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));

        service.applyPending(99L);

        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
        then(redisService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미 COMPLETED인 정산은 다시 Redis에 반영하지 않음")
    void applyPending_skipsCompletedSettlement() {
        record.markApplied();
        record.markCompleted();
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(record));

        service.applyPending(99L);

        assertThat(record.getStatus()).isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
        then(redisService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("B연합 승리면 A연합 학교별 기여도를 차감")
    void applyPending_allianceBWins() {
        AllianceMatchSettlement bWins = AllianceMatchSettlement.prepare(
                99L, 180L, 300L,
                11L, 12L, 21L, 22L,
                70L, 110L, 150L, 150L);
        when(repository.findByIdForUpdate(99L)).thenReturn(Optional.of(bWins));
        when(redisService.settleOnce(99L, 11L, 12L, 21L, 22L, 70L, 110L))
                .thenReturn(true);

        service.applyPending(99L);

        verify(redisService).settleOnce(99L, 11L, 12L, 21L, 22L, 70L, 110L);
        assertThat(bWins.getStatus()).isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }
}
