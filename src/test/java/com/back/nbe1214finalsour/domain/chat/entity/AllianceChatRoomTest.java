package com.back.nbe1214finalsour.domain.chat.entity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllianceChatRoomTest {
    @Test
    void factoryCreatesNewRoom() {
        AllianceChatRoom room = AllianceChatRoom.create(42L, "연합");
        assertThat(room.getId()).isEqualTo(42L);
        assertThat(room.getAllianceName()).isEqualTo("연합");
        assertThat(room.isNew()).isTrue();
        assertThat(room.getLastContent()).isNull();
        assertThat(room.getLastContentAt()).isNull();
    }
}
