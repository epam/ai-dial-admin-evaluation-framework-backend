package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealthWeekRange")
class HealthWeekRangeTest {

    private static final long MONDAY_2026_11_16 =
            Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();

    private static WeekRange rangeAt(String instant, int weeks) {
        return new HealthWeekRange(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)).range(weeks);
    }

    @Test
    @DisplayName("Monday 00:00:00.000 starts its own week")
    void mondayMidnightStartsOwnWeek() {
        final WeekRange range = rangeAt("2026-11-16T00:00:00.000Z", 1);

        assertThat(range.startMs()).isEqualTo(MONDAY_2026_11_16);
        assertThat(range.endMs()).isEqualTo(MONDAY_2026_11_16 + AnalyticsConstants.WEEK_MS);
        assertThat(range.weekStarts()).containsExactly(MONDAY_2026_11_16);
    }

    @Test
    @DisplayName("Sunday 23:59:59.999 still belongs to the week that started the previous Monday")
    void sundayLastMillisecondBelongsToWeek() {
        final WeekRange range = rangeAt("2026-11-22T23:59:59.999Z", 1);

        assertThat(range.startMs()).isEqualTo(MONDAY_2026_11_16);
        assertThat(range.endMs()).isEqualTo(MONDAY_2026_11_16 + AnalyticsConstants.WEEK_MS);
    }

    @Test
    @DisplayName("A mid-week instant resolves to that week's Monday, not a Thursday")
    void midWeekResolvesToMonday() {
        final WeekRange range = rangeAt("2026-11-19T12:34:56.789Z", 3);

        assertThat(range.weekStarts())
                .containsExactly(
                        MONDAY_2026_11_16 - 2 * AnalyticsConstants.WEEK_MS,
                        MONDAY_2026_11_16 - AnalyticsConstants.WEEK_MS,
                        MONDAY_2026_11_16);
        assertThat(range.startMs()).isEqualTo(MONDAY_2026_11_16 - 2 * AnalyticsConstants.WEEK_MS);
    }

    @Test
    @DisplayName("A week crossing a year change starts on the Monday in the previous year")
    void yearChangeStartsInPreviousYear() {
        final WeekRange range = rangeAt("2027-01-01T10:00:00Z", 2);

        assertThat(range.weekStarts())
                .containsExactly(
                        Instant.parse("2026-12-21T00:00:00Z").toEpochMilli(),
                        Instant.parse("2026-12-28T00:00:00Z").toEpochMilli());
        assertThat(range.endMs())
                .isEqualTo(Instant.parse("2027-01-04T00:00:00Z").toEpochMilli());
    }

    @Test
    @DisplayName("One week spans exactly WEEK_MS")
    void oneWeekSpansWeekMs() {
        final WeekRange range = rangeAt("2026-11-18T08:00:00Z", 1);

        assertThat(range.endMs() - range.startMs()).isEqualTo(AnalyticsConstants.WEEK_MS);
        assertThat(range.weekStarts()).hasSize(1);
    }

    @Test
    @DisplayName("52 weeks span exactly 52 times WEEK_MS without overflow")
    void fiftyTwoWeeksNoOverflow() {
        final WeekRange range = rangeAt("2026-11-18T08:00:00Z", 52);

        assertThat(range.endMs() - range.startMs()).isEqualTo(52L * 7L * 86_400_000L);
        assertThat(range.weekStarts()).hasSize(52);
        assertThat(range.weekStarts().getFirst()).isEqualTo(range.startMs());
        assertThat(range.weekStarts().getLast()).isEqualTo(range.endMs() - AnalyticsConstants.WEEK_MS);
    }
}
