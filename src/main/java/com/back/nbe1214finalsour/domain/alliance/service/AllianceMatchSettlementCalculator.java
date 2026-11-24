package com.back.nbe1214finalsour.domain.alliance.service;

public final class AllianceMatchSettlementCalculator {

    private AllianceMatchSettlementCalculator() {
    }

    public static Settlement calculate(
            long loserAContribution,
            long loserBContribution,
            long loserACurrentScore,
            long loserBCurrentScore
    ) {
        // 음수 입력 방지
        if (loserAContribution < 0 || loserBContribution < 0
                || loserACurrentScore < 0 || loserBCurrentScore < 0) {
            throw new IllegalArgumentException("정산 점수는 음수일 수 없습니다.");
        }

        // 패배 학교별 실제 차감 가능한 점수 계산
        long loserADeduction = Math.min(
                loserAContribution, loserACurrentScore
        );
        long loserBDeduction = Math.min(
                loserBContribution, loserBCurrentScore
        );

        // 실제 차감 총액
        long totalDeduction = Math.addExact(
                loserADeduction, loserBDeduction
        );

        // 승리 학교에 50:50으로 분배
        long winnerAReward = totalDeduction / 2;
        long winnerBReward = totalDeduction - winnerAReward;

        return new Settlement(
                loserADeduction,
                loserBDeduction,
                winnerAReward,
                winnerBReward
        );
    }

    public record Settlement(
            long loserADeduction,
            long loserBDeduction,
            long winnerAReward,
            long winnerBReward
    ) {
    }
}