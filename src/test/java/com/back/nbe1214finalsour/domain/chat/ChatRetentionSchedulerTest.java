package com.back.nbe1214finalsour.domain.chat;

import com.back.nbe1214finalsour.domain.chat.service.ChatRetentionScheduler;
import com.back.nbe1214finalsour.domain.chat.service.ChatRetentionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatRetentionSchedulerTest {
    @Test
    @DisplayName("학교 정리 실패가 전파되지 않고 연합 정리는 실행된다")
    void catchesFailure() {
        ChatRetentionService service = mock(ChatRetentionService.class);
        doThrow(new RuntimeException("실패")).when(service).deleteUniversityMessages();
        assertThatCode(() -> new ChatRetentionScheduler(service).run()).doesNotThrowAnyException();
        verify(service).deleteAllianceMessages();
    }

    @Test
    @DisplayName("연합 정리 실패도 스케줄러 밖으로 전파하지 않는다")
    void catchesAllianceFailure() {
        ChatRetentionService service = mock(ChatRetentionService.class);
        doThrow(new RuntimeException("실패")).when(service).deleteAllianceMessages();
        assertThatCode(() -> new ChatRetentionScheduler(service).run()).doesNotThrowAnyException();
        verify(service).deleteUniversityMessages();
    }
}
