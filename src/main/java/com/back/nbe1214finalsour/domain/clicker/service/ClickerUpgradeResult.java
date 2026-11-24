package com.back.nbe1214finalsour.domain.clicker.service;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;

public record ClickerUpgradeResult(
        Clicker upgradedClicker,
        Long nextLevelRequiredScore,
        long remainingScore
) {
}
