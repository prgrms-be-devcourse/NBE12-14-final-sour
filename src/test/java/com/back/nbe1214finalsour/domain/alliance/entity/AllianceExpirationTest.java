package com.back.nbe1214finalsour.domain.alliance.entity;

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
}
