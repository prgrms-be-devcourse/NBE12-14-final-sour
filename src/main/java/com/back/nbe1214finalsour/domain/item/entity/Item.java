package com.back.nbe1214finalsour.domain.item.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_type", nullable = false, length = 20)
    private PayType payType;

    // CONTRIBUTION이면 기여도, CREDIT이면 크레딧
    @Column(nullable = false)
    private long price;

    // 효과 지속시간 (초). 물약만 있고 로고 변경권은 null (영구)
    private Integer duration;

    // 클릭 점수 배율. 물약만 있고 로고 변경권은 null
    private Double multiplier;
}
