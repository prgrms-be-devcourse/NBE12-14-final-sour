package com.back.nbe1214finalsour.domain.alliance.entity;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class AllianceMatchScheduleTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-18T15:00:00+09:00");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-18T20:00:00+09:00");

    private AllianceMatch requested() {
        return AllianceMatch.request(1L, 2L, 2, START, START);
    }

    @Test
    void acceptedReservationDoesNotStartEarly() {
        AllianceMatch match = requested();
        match.schedule(NOW);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.SCHEDULED);
        assertThat(match.getStartedAt()).isNull();
        assertThat(match.getEndedAt()).isNull();
        assertThatThrownBy(() -> match.startScheduled(START.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reservationStartsAtDueTimeAndHasCorrectDuration() {
        AllianceMatch match = requested();
        match.schedule(NOW);
        match.startScheduled(START);
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.IN_PROGRESS);
        assertThat(match.getStartedAt()).isEqualTo(START);
        assertThat(match.getEndedAt()).isEqualTo(START.plusHours(2));
        assertThatThrownBy(() -> match.startScheduled(START.plusSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void canceledScheduleCannotStart() {
        AllianceMatch match = requested();
        match.schedule(NOW);
        match.cancelScheduled();
        assertThat(match.getStatus()).isEqualTo(AllianceMatchStatus.CANCELED);
        assertThatThrownBy(() -> match.startScheduled(START))
                .isInstanceOf(IllegalStateException.class);
    }
}
