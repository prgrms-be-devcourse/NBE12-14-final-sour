package com.back.nbe1214finalsour.domain.chat.entity;

import com.back.nbe1214finalsour.global.jpa.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** 연합 ID를 PK로 쓰는 alliance_chat_room. 할당 ID의 merge 대신 persist를 위해 Persistable을 구현한다. */
@Entity
@Table(name = "alliance_chat_room")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllianceChatRoom extends BaseTimeEntity implements Persistable<Long> {
    @Id
    @Column(name = "alliance_id")
    private Long id;

    @Column(name = "alliance_name", nullable = false, length = 50)
    private String allianceName;

    @Column(name = "last_content", length = 200)
    private String lastContent;

    @Column(name = "last_content_at")
    private OffsetDateTime lastContentAt;

    @Transient
    private boolean newEntity = true;

    public static AllianceChatRoom create(Long id, String name) {
        AllianceChatRoom room = new AllianceChatRoom();
        room.id = id;
        room.allianceName = name;
        return room;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markNotNew() {
        newEntity = false;
    }
}
