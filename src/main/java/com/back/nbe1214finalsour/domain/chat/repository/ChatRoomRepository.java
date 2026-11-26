package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.domain.chat.entity.ChatRoom;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    @Modifying
    @Query("update ChatRoom r set r.lastContent = :content, r.lastContentAt = :at where r.id = :id and (r.lastContentAt is null or r.lastContentAt <= :at)")
    int updateLastContent(@Param("id") Long id, @Param("content") String content, @Param("at") OffsetDateTime at);
}
