
package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceMatchFinishTest {

    @Test
    @DisplayName("대결 종료 시 A연합 점수가 높으면 A연합이 승리한다")
    void finish_selectsAllianceAAsWinner() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);

        // when: 종료 시각에 최종 점수 확정
        match.finish(startedAt.plusHours(6), 300L, 200L);

        // then: A연합 승리 및 대결 종료
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(match.getAScore()).isEqualTo(300L);
        assertThat(match.getBScore()).isEqualTo(200L);
        assertThat(match.getWinnerAllianceId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("대결 점수가 같으면 무승부로 종료되고 승리 연합이 없다")
    void finish_drawHasNoWinner() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);

        // when: 두 연합의 점수가 동일한 상태로 종료
        match.finish(startedAt.plusHours(6), 300L, 300L);

        // then: 무승부로 종료되며 승리 연합은 없음
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(match.getAScore()).isEqualTo(300L);
        assertThat(match.getBScore()).isEqualTo(300L);
        assertThat(match.getWinnerAllianceId()).isNull();
    }

    @Test
    @DisplayName("대결 종료 시 B연합 점수가 높으면 B연합이 승리한다")
    void finish_selectsAllianceBAsWinner() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);

        // when: B연합이 더 높은 점수로 종료
        match.finish(startedAt.plusHours(6), 200L, 300L);

        // then: B연합 승리 및 대결 종료
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(match.getAScore()).isEqualTo(200L);
        assertThat(match.getBScore()).isEqualTo(300L);
        assertThat(match.getWinnerAllianceId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("대결 종료 시각 이전에는 대결을 종료할 수 없다")
    void finish_rejectsBeforeEndTime() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);

        // when & then: 종료 시각 이전에 종료하면 예외 발생
        assertThatThrownBy(() ->
                match.finish(startedAt.plusHours(5), 300L, 200L)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("대결 종료 시각이 지나지 않았습니다.");

        // then: 대결 상태와 점수는 변경되지 않음
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        assertThat(match.getAScore()).isZero();
        assertThat(match.getBScore()).isZero();
        assertThat(match.getWinnerAllianceId()).isNull();
    }

    @Test
    @DisplayName("이미 종료된 대결은 다시 종료할 수 없다")
    void finish_rejectsAlreadyFinishedMatch() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);
        match.finish(startedAt.plusHours(6), 300L, 200L);

        // when & then: 이미 종료된 대결을 다시 종료하면 예외 발생
        assertThatThrownBy(() ->
                match.finish(startedAt.plusHours(7), 100L, 500L)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("진행 중인 대결만 종료할 수 있습니다.");

        // then: 기존 종료 결과는 유지됨
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.FINISHED);

        assertThat(match.getAScore()).isEqualTo(300L);
        assertThat(match.getBScore()).isEqualTo(200L);
        assertThat(match.getWinnerAllianceId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("대결 종료 시 음수 점수는 허용하지 않는다")
    void finish_rejectsNegativeScore() {
        // given: 6시간 대결 신청 및 수락
        OffsetDateTime startedAt = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                startedAt.plusHours(12)
        );

        match.accept(startedAt);

        // when & then: A연합 점수가 음수이면 예외 발생
        assertThatThrownBy(() ->
                match.finish(startedAt.plusHours(6), -100L, 200L)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대결 점수는 음수일 수 없습니다.");

        // when & then: B연합 점수가 음수여도 예외 발생
        assertThatThrownBy(() ->
                match.finish(startedAt.plusHours(6), 300L, -100L)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대결 점수는 음수일 수 없습니다.");

        // then: 잘못된 점수로 인해 대결 정보가 변경되지 않음
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        assertThat(match.getAScore()).isZero();
        assertThat(match.getBScore()).isZero();
        assertThat(match.getWinnerAllianceId()).isNull();
    }
}