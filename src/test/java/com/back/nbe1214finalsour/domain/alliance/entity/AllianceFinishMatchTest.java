package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceFinishMatchTest {

    @Test
    @DisplayName("대결 종료 후 연합 상태가 ACTIVE로 변경된다")
    void finishMatch_restoresActiveStatus() {
        // given: 대결 중인 연합
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        Alliance alliance = Alliance.create(
                "테스트연합",
                12,
                now
        );

        alliance.startMatch(now.plusHours(1));

        assertThat(alliance.getStatus())
                .isEqualTo(AllianceStatus.IN_MATCH);

        // when: 대결 종료
        alliance.finishMatch();

        // then: 연합 상태가 ACTIVE로 복구됨
        assertThat(alliance.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
    }

    @Test
    @DisplayName("대결 중이 아닌 연합은 대결을 종료할 수 없다")
    void finishMatch_rejectsActiveAlliance() {
        // given: 대결 중이 아닌 연합
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        Alliance alliance = Alliance.create(
                "테스트연합",
                12,
                now
        );

        // when & then: ACTIVE 상태에서 종료 시도
        assertThatThrownBy(alliance::finishMatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("대결 중인 연합만 종료할 수 있습니다.");

        // then: 기존 상태 유지
        assertThat(alliance.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 대결을 종료한 연합은 다시 종료할 수 없다")
    void finishMatch_rejectsAlreadyFinishedAlliance() {
        // given: 대결을 시작하고 종료한 연합
        OffsetDateTime now = OffsetDateTime.parse(
                "2026-11-17T12:00:00+09:00"
        );

        Alliance alliance = Alliance.create(
                "테스트연합",
                12,
                now
        );

        alliance.startMatch(now.plusHours(1));
        alliance.finishMatch();

        // when & then: 중복 종료 시도
        assertThatThrownBy(alliance::finishMatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("대결 중인 연합만 종료할 수 있습니다.");

        // then: ACTIVE 상태 유지
        assertThat(alliance.getStatus())
                .isEqualTo(AllianceStatus.ACTIVE);
    }
}