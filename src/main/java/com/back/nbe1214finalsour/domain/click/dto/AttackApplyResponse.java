package com.back.nbe1214finalsour.domain.click.dto;

public record AttackApplyResponse(
        Long damage,
        Long univTotal,
        Long univRank,
        Long myAttackScore,
        Long attackLastSecond,
        Long clickLastSecond,
        Long assistLastSecond
) {
}
