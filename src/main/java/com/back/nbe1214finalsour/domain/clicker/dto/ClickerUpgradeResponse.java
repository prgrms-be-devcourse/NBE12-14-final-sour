package com.back.nbe1214finalsour.domain.clicker.dto;

import com.back.nbe1214finalsour.domain.clicker.service.ClickerUpgradeResult;

public record ClickerUpgradeResponse(
        Long level,
        int clickPower,
        Long nextLevelRequiredScore,   // 최고 레벨에 도달하면 null
        long myContribScore
) {
    public ClickerUpgradeResponse(ClickerUpgradeResult result) {
        this(
                result.upgradedClicker().getId(),
                result.upgradedClicker().getClickPower(),
                result.nextLevelRequiredScore(),
                result.remainingScore()
        );
    }
}