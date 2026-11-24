package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllianceMatchAdditionalTest {
    @Test
    void cancelRequested_changesStatus() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-16T13:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, now.plusHours(8));
        match.cancelRequested();
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        assertThat(match.getStartedAt()).isNull();
        assertThatThrownBy(match::cancelRequested).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accept_alreadyStarted_rejected() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-16T13:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, now.plusHours(8));
        match.accept(now.plusHours(1));
        assertThatThrownBy(() -> match.accept(now.plusHours(2))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accept_expired_rejected() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-16T13:00:00+09:00");
        AllianceMatch match = AllianceMatch.request(1L, 2L, 6, now.plusHours(8));
        assertThatThrownBy(() -> match.accept(now.plusHours(8))).isInstanceOf(IllegalStateException.class);
    }
}
