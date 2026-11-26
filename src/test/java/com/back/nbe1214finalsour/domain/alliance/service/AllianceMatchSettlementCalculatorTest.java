
package com.back.nbe1214finalsour.domain.alliance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceMatchSettlementCalculatorTest {

    @Test
    @DisplayName("패배 학교별 기여 점수를 차감하고 승리 학교에 절반씩 지급한다")
    void calculate_distributesActualDeductionEqually() {
        // given: 패배 측 대결 기여도와 현재 학교 점수
        long loserAContribution = 100L;
        long loserBContribution = 200L;
        long loserACurrentScore = 500L;
        long loserBCurrentScore = 400L;

        // when: 정산 금액 계산
        AllianceMatchSettlementCalculator.Settlement result =
                AllianceMatchSettlementCalculator.calculate(
                        loserAContribution,
                        loserBContribution,
                        loserACurrentScore,
                        loserBCurrentScore
                );

        // then: 패배 학교별 실제 기여 점수 차감
        assertThat(result.loserADeduction()).isEqualTo(100L);
        assertThat(result.loserBDeduction()).isEqualTo(200L);

        // then: 실제 차감 총액을 승리 학교에 50:50 지급
        assertThat(result.winnerAReward()).isEqualTo(150L);
        assertThat(result.winnerBReward()).isEqualTo(150L);
    }

    @Test
    @DisplayName("패배 학교의 점수가 부족하면 실제 보유 점수까지만 차감한다")
    void calculate_limitsDeductionToCurrentScore() {
        // given: 패배 학교 A의 현재 점수가 기여도보다 적음
        long loserAContribution = 100L;
        long loserBContribution = 200L;
        long loserACurrentScore = 50L;
        long loserBCurrentScore = 400L;

        // when: 정산 금액 계산
        AllianceMatchSettlementCalculator.Settlement result =
                AllianceMatchSettlementCalculator.calculate(
                        loserAContribution,
                        loserBContribution,
                        loserACurrentScore,
                        loserBCurrentScore
                );

        // then: 현재 점수를 초과해서 차감하지 않음
        assertThat(result.loserADeduction()).isEqualTo(50L);
        assertThat(result.loserBDeduction()).isEqualTo(200L);

        // then: 실제 차감된 250점을 절반씩 지급
        assertThat(result.winnerAReward()).isEqualTo(125L);
        assertThat(result.winnerBReward()).isEqualTo(125L);
    }

    @Test
    @DisplayName("패배 학교의 점수가 0이면 차감하지 않고 나머지 학교의 차감 점수만 보상한다")
    void calculate_handlesZeroCurrentScore() {
        // given: 패배 학교 A의 현재 점수가 0
        long loserAContribution = 100L;
        long loserBContribution = 200L;
        long loserACurrentScore = 0L;
        long loserBCurrentScore = 200L;

        // when: 정산 금액 계산
        AllianceMatchSettlementCalculator.Settlement result =
                AllianceMatchSettlementCalculator.calculate(
                        loserAContribution,
                        loserBContribution,
                        loserACurrentScore,
                        loserBCurrentScore
                );

        // then: 점수가 없는 학교는 차감하지 않음
        assertThat(result.loserADeduction()).isZero();
        assertThat(result.loserBDeduction()).isEqualTo(200L);

        // then: 실제 차감 총액 200점을 절반씩 지급
        assertThat(result.winnerAReward()).isEqualTo(100L);
        assertThat(result.winnerBReward()).isEqualTo(100L);
    }

    @Test
    @DisplayName("패배 학교의 대결 기여도가 0이면 점수를 차감하거나 보상하지 않는다")
    void calculate_handlesZeroContribution() {
        // given: 패배 학교의 대결 기여도가 모두 0
        long loserAContribution = 0L;
        long loserBContribution = 0L;
        long loserACurrentScore = 500L;
        long loserBCurrentScore = 1000L;

        // when: 정산 금액 계산
        AllianceMatchSettlementCalculator.Settlement result =
                AllianceMatchSettlementCalculator.calculate(
                        loserAContribution,
                        loserBContribution,
                        loserACurrentScore,
                        loserBCurrentScore
                );

        // then: 패배 학교 점수 차감 없음
        assertThat(result.loserADeduction()).isZero();
        assertThat(result.loserBDeduction()).isZero();

        // then: 승리 학교 보상 없음
        assertThat(result.winnerAReward()).isZero();
        assertThat(result.winnerBReward()).isZero();
    }

    @Test
    @DisplayName("정산 계산 시 음수 점수는 허용하지 않는다")
    void calculate_rejectsNegativeScore() {
        // given: 음수 기여도 또는 음수 보유 점수

        // when & then: 음수 기여도가 전달되면 예외 발생
        assertThatThrownBy(() ->
                AllianceMatchSettlementCalculator.calculate(
                        -100L, 200L, 500L, 400L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 점수는 음수일 수 없습니다.");

        // when & then: 음수 보유 점수가 전달되어도 예외 발생
        assertThatThrownBy(() ->
                AllianceMatchSettlementCalculator.calculate(
                        100L, 200L, 500L, -50L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 점수는 음수일 수 없습니다.");
    }

    @Test
    @DisplayName("패배 학교 B의 기여도가 음수이면 정산 계산을 거부한다")
    void calculate_rejectsNegativeSecondContribution() {
        assertThatThrownBy(() ->
                AllianceMatchSettlementCalculator.calculate(
                        100L, -1L, 500L, 400L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 점수는 음수일 수 없습니다.");
    }

    @Test
    @DisplayName("패배 학교 A의 현재 점수가 음수이면 정산 계산을 거부한다")
    void calculate_rejectsNegativeFirstCurrentScore() {
        assertThatThrownBy(() ->
                AllianceMatchSettlementCalculator.calculate(
                        100L, 200L, -1L, 400L
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 점수는 음수일 수 없습니다.");
    }

    @Test
    @DisplayName("차감 총액이 홀수이면 승리 학교 B에 나머지 1점을 지급한다")
    void calculate_distributesOddDeductionWithoutLosingPoint() {
        AllianceMatchSettlementCalculator.Settlement result =
                AllianceMatchSettlementCalculator.calculate(
                        1L, 2L, 10L, 10L
                );

        assertThat(result.loserADeduction()).isEqualTo(1L);
        assertThat(result.loserBDeduction()).isEqualTo(2L);
        assertThat(result.winnerAReward()).isEqualTo(1L);
        assertThat(result.winnerBReward()).isEqualTo(2L);
        assertThat(result.winnerAReward() + result.winnerBReward())
                .isEqualTo(result.loserADeduction() + result.loserBDeduction());
    }

    @Test
    @DisplayName("실제 차감 총액이 long 범위를 초과하면 예외가 발생한다")
    void calculate_rejectsScoreOverflow() {
        // given: 두 학교의 차감 총액이 long 최댓값을 초과하는 상황
        long loserAContribution = Long.MAX_VALUE;
        long loserBContribution = 1L;
        long loserACurrentScore = Long.MAX_VALUE;
        long loserBCurrentScore = 1L;

        // when & then: 점수 합산 시 오버플로 예외 발생
        assertThatThrownBy(() ->
                AllianceMatchSettlementCalculator.calculate(
                        loserAContribution,
                        loserBContribution,
                        loserACurrentScore,
                        loserBCurrentScore
                )
        )
                .isInstanceOf(ArithmeticException.class);
    }
}