package com.back.nbe1214finalsour.domain.item.dto;

import com.back.nbe1214finalsour.domain.item.entity.Item;
import com.back.nbe1214finalsour.domain.item.entity.ItemType;
import com.back.nbe1214finalsour.domain.item.entity.PayType;

public record ItemResponse(
        Long id,
        String name,
        String content,
        ItemType type,
        PayType payType,
        Long price,
        Integer durationSeconds
) {
    public ItemResponse(Item item){
        this(
                item.getId(),
                item.getName(),
                item.getContent(),
                item.getType(),
                item.getPayType(),
                item.getPrice(),
                item.getDuration()
        );
    }
}
