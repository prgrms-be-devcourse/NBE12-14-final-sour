package com.back.nbe1214finalsour.domain.chat.service;

import com.back.nbe1214finalsour.domain.alliance.entity.AllianceStatus;
import com.back.nbe1214finalsour.domain.chat.repository.ChatMessageRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.IntSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 채팅 메시지 보관 기간 정리. 연합 만료 시각의 원본은 연합이며, 노출은 소속 검사가 막는다.
 * 스키마 변경 없이 EXPIRED 상태를 사용하므로 정산 미완료로 만료가 미뤄진 연합도 기존 규칙을 따른다.
 * 여러 인스턴스의 삭제는 멱등이므로 분산 락을 사용하지 않는다.
 */
@Slf4j
@Service
public class ChatRetentionService {
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100;
    private final ChatMessageRepository messages;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final int maxBatches;

    @Autowired
    public ChatRetentionService(ChatMessageRepository messages, Clock clock, PlatformTransactionManager manager) {
        this(messages, clock, manager, DEFAULT_BATCH_SIZE, MAX_BATCHES);
    }

    public ChatRetentionService(ChatMessageRepository messages, Clock clock, PlatformTransactionManager manager,
                                int batchSize, int maxBatches) {
        if (batchSize < 1 || maxBatches < 1) throw new IllegalArgumentException("배치 크기와 횟수는 양수여야 합니다");
        this.messages = messages;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int deleteUniversityMessages() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(ChatMessageService.UNIVERSITY_RETENTION);
        int deleted = deleteBatches(() -> messages.deleteExpiredUniversityBatch(cutoff, batchSize));
        if (deleted > 0) log.info("학교 채팅 메시지 {}건 삭제", deleted);
        return deleted;
    }

    public int deleteAllianceMessages() {
        int deleted = deleteBatches(() -> messages.deleteAllianceBatch(AllianceStatus.EXPIRED.name(), batchSize));
        if (deleted > 0) log.info("연합 채팅 메시지 {}건 삭제", deleted);
        return deleted;
    }

    private int deleteBatches(IntSupplier operation) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int count = Objects.requireNonNull(transaction.execute(status -> operation.getAsInt()), "삭제 트랜잭션 결과가 없습니다");
            total += count;
            if (count < batchSize) break;
        }
        return total;
    }
}
