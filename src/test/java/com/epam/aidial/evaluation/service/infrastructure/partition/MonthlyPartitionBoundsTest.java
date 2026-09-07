package com.epam.aidial.evaluation.service.infrastructure.partition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MonthlyPartitionBounds")
class MonthlyPartitionBoundsTest {

    @Test
    @DisplayName("partitionName builds <table>_p<yyyyMM>")
    void partitionName_buildsExpectedName() {
        assertThat(MonthlyPartitionBounds.partitionName("test_case_run_results", YearMonth.of(2026, 9)))
                .isEqualTo("test_case_run_results_p202609");
    }

    @Test
    @DisplayName("lowerBoundMs and upperBoundMs are one calendar month apart in UTC")
    void bounds_oneMonthApart() {
        long lower = MonthlyPartitionBounds.lowerBoundMs(YearMonth.of(2026, 9));
        long upper = MonthlyPartitionBounds.upperBoundMs(YearMonth.of(2026, 9));

        assertThat(Instant.ofEpochMilli(lower).toString()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(Instant.ofEpochMilli(upper).toString()).isEqualTo("2026-10-01T00:00:00Z");
    }

    @Test
    @DisplayName("upperBoundMs rolls over the calendar year at December")
    void upperBoundMs_rollsOverYear() {
        long upper = MonthlyPartitionBounds.upperBoundMs(YearMonth.of(2026, 12));

        assertThat(Instant.ofEpochMilli(upper).toString()).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("bounds handle a leap February correctly")
    void bounds_leapFebruary() {
        long lower = MonthlyPartitionBounds.lowerBoundMs(YearMonth.of(2028, 2));
        long upper = MonthlyPartitionBounds.upperBoundMs(YearMonth.of(2028, 2));

        assertThat(Instant.ofEpochMilli(lower).toString()).isEqualTo("2028-02-01T00:00:00Z");
        assertThat(Instant.ofEpochMilli(upper).toString()).isEqualTo("2028-03-01T00:00:00Z");
    }

    @Test
    @DisplayName("monthsToEnsure returns the current month plus each look-ahead month, ascending")
    void monthsToEnsure_returnsCurrentPlusLookAhead() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.monthsToEnsure(now, 2))
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11));
    }

    @Test
    @DisplayName("monthsToEnsure with zero look-ahead returns only the current month")
    void monthsToEnsure_zeroLookAhead_returnsOnlyCurrentMonth() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.monthsToEnsure(now, 0)).containsExactly(YearMonth.of(2026, 9));
    }

    @Test
    @DisplayName("monthsToEnsure rolls over the calendar year at December look-ahead")
    void monthsToEnsure_rollsOverYear() {
        Instant now = Instant.parse("2026-12-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.monthsToEnsure(now, 2))
                .containsExactly(YearMonth.of(2026, 12), YearMonth.of(2027, 1), YearMonth.of(2027, 2));
    }

    @Test
    @DisplayName("a partition ending exactly at the retention horizon is expired")
    void expiredBefore_partitionEndingAtHorizon_isExpired() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z"); // current month = 2026-09
        // retention=3 -> horizon month = 2026-06; partition 2026-05 ends 2026-06-01 == horizon start
        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2026, 5), now, 3))
                .isTrue();
    }

    @Test
    @DisplayName("a partition ending one month after the retention horizon is not expired")
    void expiredBefore_partitionEndingAfterHorizon_isNotExpired() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z"); // current month = 2026-09
        // retention=3 -> horizon month = 2026-06; partition 2026-06 ends 2026-07-01, after horizon start
        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2026, 6), now, 3))
                .isFalse();
    }

    @Test
    @DisplayName("the current month's partition is never expired under an active retention window")
    void expiredBefore_currentMonth_isNeverExpired() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2026, 9), now, 1))
                .isFalse();
    }

    @Test
    @DisplayName(
            "retention of zero never expires any month, including far in the past (disabled sentinel, not a zero-width horizon)")
    void expiredBefore_zeroRetention_neverExpiresAnything() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2020, 1), now, 0))
                .isFalse();
        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2026, 8), now, 0))
                .isFalse();
        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2026, 9), now, 0))
                .isFalse();
    }

    @Test
    @DisplayName("negative retention (defensive) never expires any month")
    void expiredBefore_negativeRetention_neverExpiresAnything() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");

        assertThat(MonthlyPartitionBounds.expiredBefore(YearMonth.of(2020, 1), now, -1))
                .isFalse();
    }

    @Test
    @DisplayName("currentUtcMonth reads the month from a UTC instant")
    void currentUtcMonth_readsMonthFromInstant() {
        assertThat(MonthlyPartitionBounds.currentUtcMonth(Instant.parse("2026-01-31T23:59:59Z")))
                .isEqualTo(YearMonth.of(2026, 1));
    }
}
