package com.back.nbe1214finalsour.domain.chat.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 전송 시점의 작성자 정보와 방 ID를 저장하는 메시지. */
@Entity
@Table(name = "chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id")
    private Long chatRoomId;

    @Column(name = "alliance_chat_room_id")
    private Long allianceChatRoomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "university_name", nullable = false, length = 100)
    private String universityName;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(nullable = false, length = 200)
    private String content;

    private static ChatMessage create(Long userId, String nickname, String universityName, String content) {
        ChatMessage message = new ChatMessage();
        message.userId = userId;
        message.nickname = nickname;
        message.universityName = universityName;
        message.content = content;
        return message;
    }

    public static ChatMessage toUniversity(Long roomId, Long userId, String nickname, String universityName, String content) {
        ChatMessage message = create(userId, nickname, universityName, content);
        message.chatRoomId = roomId;
        return message;
    }

    public static ChatMessage toAlliance(Long roomId, Long userId, String nickname, String universityName, String content) {
        ChatMessage message = create(userId, nickname, universityName, content);
        message.allianceChatRoomId = roomId;
        return message;
    }

    public String roomId() {
        return chatRoomId != null ? ChatRoomId.university(chatRoomId) : ChatRoomId.alliance(allianceChatRoomId);
    }
}
