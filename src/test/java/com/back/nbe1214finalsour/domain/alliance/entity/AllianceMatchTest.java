package com.back.nbe1214finalsour.domain.alliance.entity;

import com.back.nbe1214finalsour.global.exception.BusinessException;
import com.back.nbe1214finalsour.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceMatchTest {

    @Test
    @DisplayName("대결을 신청하면 REQUESTED 상태로 생성된다")
    void request_createsRequestedMatch() {
        // given: 두 연합과 신청 조건
        Long allianceAId = 1L;
        Long allianceBId = 2L;
        int durationHours = 6;
        OffsetDateTime requestExpiredAt =
                OffsetDateTime.now().plusHours(12);

        // when: 대결 신청 생성
        AllianceMatch match = AllianceMatch.request(
                allianceAId,
                allianceBId,
                durationHours,
                requestExpiredAt
        );

        // then: 생성 결과 확인
        assertThat(match.getAllianceAId()).isEqualTo(1L);
        assertThat(match.getAllianceBId()).isEqualTo(2L);
        assertThat(match.getDurationHours()).isEqualTo(6);
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.REQUESTED);
        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();
        assertThat(match.getAScore()).isZero();
        assertThat(match.getBScore()).isZero();
    }

    @Test
    @DisplayName("같은 연합끼리는 대결을 신청할 수 없다")
    void request_rejectsSameAlliance() {
        // given
        Long allianceId = 1L;
        OffsetDateTime deadline = OffsetDateTime.now().plusHours(12);

        // when & then
        assertThatThrownBy(() ->
                AllianceMatch.request(
                        allianceId,
                        allianceId,
                        6,
                        deadline
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대결 시간은 1~24시간이어야 한다")
    void request_rejectsInvalidDuration() {
        // given
        OffsetDateTime deadline = OffsetDateTime.now().plusHours(12);

        // when & then: 0시간은 불가능
        assertThatThrownBy(() ->
                AllianceMatch.request(1L, 2L, 0, deadline)
        ).isInstanceOf(IllegalArgumentException.class);

        // when & then: 25시간도 불가능
        assertThatThrownBy(() ->
                AllianceMatch.request(1L, 2L, 25, deadline)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("신청 마감 시각이 없으면 대결을 생성할 수 없다")
    void request_rejectsNullDeadline() {
        // when & then
        assertThatThrownBy(() ->
                AllianceMatch.request(1L, 2L, 6, null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("대결을 수락하면 IN_PROGRESS 상태로 변경되고 시작·종료 시각이 설정된다")
    void accept_startsMatch() {
        // given: 6시간 대결 신청
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-16T13:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L,
                2L,
                6,
                now.plusHours(12)
        );

        // when: 1시간 후 대결 수락
        OffsetDateTime acceptedAt = now.plusHours(1);
        match.accept(acceptedAt);

        // then: 진행 중 상태로 변경
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        // 시작 시각 = 수락 시각
        assertThat(match.getStartedAt())
                .isEqualTo(acceptedAt);

        // 종료 시각 = 수락 시각 + 6시간
        assertThat(match.getEndedAt())
                .isEqualTo(acceptedAt.plusHours(6));

        // 대결 점수는 처음에 0
        assertThat(match.getAScore()).isZero();
        assertThat(match.getBScore()).isZero();
    }

    @Test
    @DisplayName("이미 시작한 대결은 다시 수락할 수 없다")
    void accept_rejectsAlreadyStartedMatch() {
        // given: 대결 신청
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-16T13:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L, 2L, 6, now.plusHours(12)
        );

        // 첫 번째 수락은 정상 처리
        match.accept(now.plusHours(1));

        // when & then: 두 번째 수락은 실패
        assertThatThrownBy(() ->
                match.accept(now.plusHours(2))
        ).isInstanceOf(IllegalStateException.class);

        // 상태는 그대로 유지
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.IN_PROGRESS);

        assertThat(match.getStartedAt())
                .isEqualTo(now.plusHours(1));
    }

    @Test
    @DisplayName("신청 마감 시각이 지나면 대결을 수락할 수 없다")
    void accept_rejectsExpiredRequest() {
        // given: 대결 신청
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-16T13:00:00+09:00"
        );

        AllianceMatch match = AllianceMatch.request(
                1L, 2L, 6, now.plusHours(12)
        );

        // when & then: 마감 시각에 수락 시도
        assertThatThrownBy(() ->
                match.accept(now.plusHours(12))
        ).isInstanceOf(IllegalStateException.class);

        // 대결이 시작되지 않아야 함
        assertThat(match.getStatus())
                .isEqualTo(AllianceMatchStatus.REQUESTED);

        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1, 3, 4",
            "1, 2, 1, 4",
            "1, 2, 3, 0"
    })
    @DisplayName("참가 학교가 중복되거나 유효하지 않으면 대결을 시작하지 않는다")
    void startWithRoster_rejectsInvalidMembers(long a1, long a2, long b1, long b2) {
        // given: 시작 가능한 대결 신청
        OffsetDateTime now = OffsetDateTime.parse("2026-11-16T13:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, now.plusHours(12));

        // when & then: 잘못된 참가 학교 명단으로 시작 시도
        assertThatThrownBy(() -> match.startWithRoster(now, a1, a2, b1, b2))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode())
                                .isEqualTo(ErrorCode.MATCH_MEMBERS_INCOMPLETE));

        // 대결 상태, 시각, 참가 학교 명단 모두 변경되지 않음
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.REQUESTED);
        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();
        assertThat(match.getAUniversity1Id()).isNull();
        assertThat(match.getAUniversity2Id()).isNull();
        assertThat(match.getBUniversity1Id()).isNull();
        assertThat(match.getBUniversity2Id()).isNull();
    }

    @Test
    @DisplayName("시작된 대결의 참가 학교 명단과 시작·종료 시각은 다시 변경할 수 없다")
    void startWithRoster_preservesConfirmedMembersOnRestart() {
        // given: 참가 학교 네 곳이 확정된 대결
        OffsetDateTime now = OffsetDateTime.parse("2026-11-16T13:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, now.plusHours(12));
        match.startWithRoster(now, 1L, 2L, 3L, 4L);

        // when & then: 다른 참가 학교 명단으로 다시 시작 시도
        assertThatThrownBy(() -> match.startWithRoster(now.plusHours(1), 5L, 6L, 7L, 8L))
                .isInstanceOf(IllegalStateException.class);

        // 최초 확정된 정산 대상과 대결 시각 유지
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(match.getStartedAt()).isEqualTo(now);
        assertThat(match.getEndedAt()).isEqualTo(now.plusHours(6));
        assertThat(match.getAUniversity1Id()).isEqualTo(1L);
        assertThat(match.getAUniversity2Id()).isEqualTo(2L);
        assertThat(match.getBUniversity1Id()).isEqualTo(3L);
        assertThat(match.getBUniversity2Id()).isEqualTo(4L);
    }
}
