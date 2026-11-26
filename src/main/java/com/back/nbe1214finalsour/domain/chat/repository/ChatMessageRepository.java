package com.back.nbe1214finalsour.domain.chat.repository;

import com.back.nbe1214finalsour.domain.chat.entity.ChatMessage;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Modifying
    @Query(value = "DELETE FROM chat_message WHERE id IN (SELECT id FROM chat_message WHERE chat_room_id IS NOT NULL AND created_at < :cutoff ORDER BY created_at, id LIMIT :batch)", nativeQuery = true)
    int deleteExpiredUniversityBatch(@Param("cutoff") OffsetDateTime cutoff, @Param("batch") int batch);

    /**
     * 순서 없이 지운다. {@code ORDER BY id}를 붙이면 플래너가 PK 인덱스를 순서대로 훑어, 지울 행이 없을 때 테이블 전체를 읽는다.
     */
    @Modifying
    @Query(value = "DELETE FROM chat_message WHERE id IN (SELECT id FROM chat_message WHERE alliance_chat_room_id IN (SELECT id FROM alliance WHERE status = :status) LIMIT :batch)", nativeQuery = true)
    int deleteAllianceBatch(@Param("status") String status, @Param("batch") int batch);

    List<ChatMessage> findByChatRoomIdOrderByIdDesc(Long roomId, Pageable page);
    List<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long cursor, Pageable page);
    List<ChatMessage> findByAllianceChatRoomIdOrderByIdDesc(Long roomId, Pageable page);
    List<ChatMessage> findByAllianceChatRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long cursor, Pageable page);
}
