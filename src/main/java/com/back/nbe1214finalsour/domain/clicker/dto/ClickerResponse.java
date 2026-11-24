package com.back.nbe1214finalsour.domain.clicker.dto;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;

public record ClickerResponse(
        Long level,
        int clickPower,
        long requiredScore
) {
    public ClickerResponse(Clicker clicker){
        this(
                clicker.getId(),
                clicker.getClickPower(),
                clicker.getRequiredScore()
        );
    }
}
