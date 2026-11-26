package com.back.nbe1214finalsour.domain.university.scheduler;

import com.back.nbe1214finalsour.domain.university.service.RankSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RankSnapshotSchedulerTest {

    @Mock
    private RankSnapshotService rankSnapshotService;

    @InjectMocks
    private RankSnapshotScheduler scheduler;

    @Test
    @DisplayName("자정 실행 시 순위 복사본 생성을 요청한다")
    void daily_requestsSnapshot() {
        // given
        given(rankSnapshotService.snapshotIfMissing()).willReturn(true);

        // when
        scheduler.daily();

        // then
        then(rankSnapshotService).should().snapshotIfMissing();
    }

    @Test
    @DisplayName("서버 시작 시 순위 복사본 생성을 요청하고 생성이 불필요하면 종료한다")
    void catchUp_skipsWhenSnapshotNotCreated() {
        // given
        given(rankSnapshotService.snapshotIfMissing()).willReturn(false);

        // when
        scheduler.catchUp();

        // then
        then(rankSnapshotService).should().snapshotIfMissing();
    }

    @Test
    @DisplayName("서버 시작 시 순위 복사본 생성에 실패해도 예외를 전파하지 않는다")
    void catchUp_handlesSnapshotFailure() {
        // given
        given(rankSnapshotService.snapshotIfMissing())
                .willThrow(new IllegalStateException("순위 복사본 생성 실패"));

        // when & then
        assertThatCode(scheduler::catchUp).doesNotThrowAnyException();
        then(rankSnapshotService).should().snapshotIfMissing();
    }
}
