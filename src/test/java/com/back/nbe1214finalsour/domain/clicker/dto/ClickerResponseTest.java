package com.back.nbe1214finalsour.domain.clicker.dto;

import com.back.nbe1214finalsour.domain.clicker.entity.Clicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClickerResponseTest {
    @Test
    @DisplayName("Clicker 엔티티를 응답 DTO로 변환하면 id가 level로 변경")
    public void t1() {
        Clicker clicker = new Clicker(2L, 20, 300L);

        ClickerResponse response = new ClickerResponse(clicker);

        assertThat(response.level()).isEqualTo(2L);         // 엔티티 id → level
        assertThat(response.clickPower()).isEqualTo(20);
        assertThat(response.requiredScore()).isEqualTo(300L);
    }
}
