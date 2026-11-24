package com.back.nbe1214finalsour.domain.clicker.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clicker")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Clicker {
    @Id
    private Long id;    // Level

    private int clickPower;

    private long requiredScore; // 해당 Level로 업그레이드 시 차감하는 점수 (가격 느낌)
}
