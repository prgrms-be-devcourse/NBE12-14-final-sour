package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class AllianceExpirationTest {
    @Test
    void dueAllianceExpires() {
        OffsetDateTime now = OffsetDateTime.now();
        Alliance alliance = Alliance.create("테스트연합", 6, now.minusHours(7));
        alliance.expire(now);
        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.EXPIRED);
    }

    @Test
    void allianceCannotExpireBeforeDeadline() {
        OffsetDateTime now = OffsetDateTime.now();
        Alliance alliance = Alliance.create("테스트연합", 6, now);
        assertThatThrownBy(() -> alliance.expire(now.plusHours(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("만료 시각이 되어도 대결 중인 연합은 정산 전에 만료할 수 없다")
    void allianceCannotExpireBeforeMatchSettlement() {
        // given: 만료 시각이 되었지만 아직 대결 중인 연합
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-25T10:00:00+09:00");
        Alliance alliance = Alliance.create("테스트연합", 6, createdAt);
        alliance.startMatch(createdAt.plusHours(1));

        // when & then: 진행 중인 대결을 정산하기 전에는 만료할 수 없다.
        assertThatThrownBy(() -> alliance.expire(createdAt.plusHours(6)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("진행 중인 대결을 먼저 정산해야 합니다.");
        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.IN_MATCH);

        // 대결 종료 상태로 복구된 후에는 만료할 수 있다.
        alliance.finishMatch();
        alliance.expire(createdAt.plusHours(6));
        assertThat(alliance.getStatus()).isEqualTo(AllianceStatus.EXPIRED);
    }
}
