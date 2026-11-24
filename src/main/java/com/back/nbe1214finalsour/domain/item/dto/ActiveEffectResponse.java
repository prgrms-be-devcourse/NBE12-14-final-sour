package com.back.nbe1214finalsour.domain.item.dto;

import com.back.nbe1214finalsour.domain.item.service.ActiveEffect;

import java.time.OffsetDateTime;

// 적용 중인 아이템 효과 응답. expiredAt은 jackson.time-zone 설정으로 KST 오프셋으로 나간다
public record ActiveEffectResponse(
        Long itemId,
        String name,
        double multiplier,
        OffsetDateTime expiredAt,
        long remainingSeconds
) {
    public static ActiveEffectResponse from(ActiveEffect effect) {
        return new ActiveEffectResponse(
                effect.itemId(),
                effect.name(),
                effect.multiplier(),
                effect.expiredAt(),
                effect.remainingSeconds()
        );
    }
}