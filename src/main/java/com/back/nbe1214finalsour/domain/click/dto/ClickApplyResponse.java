package com.back.nbe1214finalsour.domain.click.dto;

public record ClickApplyResponse(
        Long univTotal,
        Long deptTotal,
        Long myContrib,
        Long univRank,
        Long lastSecond
) {
}
