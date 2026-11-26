package com.back.nbe1214finalsour.domain.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageTest {
    @Test
    @DisplayName("학교 메시지 팩토리는 학교 방 ID만 채운다")
    void universityFactoryUsesOnlyUniversityRoom() {
        ChatMessage message = ChatMessage.toUniversity(12L, 1024L, "대대대짱", "서울대학교", "안녕");
        assertThat(message.getChatRoomId()).isEqualTo(12L);
        assertThat(message.getAllianceChatRoomId()).isNull();
        assertThat(message.roomId()).isEqualTo("UNIV#12");
        assertThat(message.getUserId()).isEqualTo(1024L);
        assertThat(message.getNickname()).isEqualTo("대대대짱");
        assertThat(message.getUniversityName()).isEqualTo("서울대학교");
        assertThat(message.getContent()).isEqualTo("안녕");
        assertThat(message.getId()).isNull();
    }

    @Test
    @DisplayName("연합 메시지 팩토리는 연합 방 ID만 채운다")
    void allianceFactoryUsesOnlyAllianceRoom() {
        ChatMessage message = ChatMessage.toAlliance(7L, 1024L, "대대대짱", "서울대학교", "안녕");
        assertThat(message.getChatRoomId()).isNull();
        assertThat(message.getAllianceChatRoomId()).isEqualTo(7L);
        assertThat(message.roomId()).isEqualTo("ALLIANCE#7");
    }
}
