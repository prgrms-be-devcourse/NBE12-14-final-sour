package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceMatchSettlementStatusTest {

    private AllianceMatchSettlement createSettlement() {
        return AllianceMatchSettlement.prepare(
                99L,
                300L,
                200L,
                1L,
                2L,
                3L,
                4L,
                120L,
                180L,
                80L,
                120L
        );
    }

    @Test
    @DisplayName("Redis 정산 적용 후 PENDING에서 APPLIED로 변경된다")
    void markApplied_changesPendingToApplied() {
        // given: PENDING 상태의 정산
        AllianceMatchSettlement settlement = createSettlement();

        // when: Redis 정산 적용 확인
        settlement.markApplied();

        // then: APPLIED 상태로 변경
        assertThat(settlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }

    @Test
    @DisplayName("정산 완료 시 APPLIED에서 COMPLETED로 변경된다")
    void markCompleted_changesAppliedToCompleted() {
        // given: Redis 정산이 적용된 상태
        AllianceMatchSettlement settlement = createSettlement();
        settlement.markApplied();

        // when: 대결 종료 및 정산 완료
        settlement.markCompleted();

        // then: COMPLETED 상태로 변경
        assertThat(settlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("PENDING 상태에서 바로 COMPLETED로 변경할 수 없다")
    void markCompleted_rejectsPendingStatus() {
        // given: Redis 정산이 아직 적용되지 않은 상태
        AllianceMatchSettlement settlement = createSettlement();

        // when & then: 정산 완료 처리 거부
        assertThatThrownBy(settlement::markCompleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("적용된 정산만 완료할 수 있습니다.");

        // then: 기존 상태 유지
        assertThat(settlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.PENDING);
    }

    @Test
    @DisplayName("이미 완료된 정산은 다시 완료할 수 없다")
    void markCompleted_rejectsAlreadyCompleted() {
        // given: 정산 완료 상태
        AllianceMatchSettlement settlement = createSettlement();
        settlement.markApplied();
        settlement.markCompleted();

        // when & then: 중복 완료 처리 거부
        assertThatThrownBy(settlement::markCompleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("적용된 정산만 완료할 수 있습니다.");

        // then: COMPLETED 상태 유지
        assertThat(settlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.COMPLETED);
    }

    @Test
    @DisplayName("이미 적용된 정산은 다시 APPLIED로 변경할 수 없다")
    void markApplied_rejectsAlreadyApplied() {
        // given: APPLIED 상태
        AllianceMatchSettlement settlement = createSettlement();
        settlement.markApplied();

        // when & then: 중복 적용 상태 변경 거부
        assertThatThrownBy(settlement::markApplied)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("대기 중인 정산만 적용할 수 있습니다.");

        // then: 기존 상태 유지
        assertThat(settlement.getStatus())
                .isEqualTo(AllianceMatchSettlementStatus.APPLIED);
    }
}