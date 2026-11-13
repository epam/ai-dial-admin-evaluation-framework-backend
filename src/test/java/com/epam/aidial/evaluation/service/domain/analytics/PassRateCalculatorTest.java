package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCounts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PassRateCalculator")
class PassRateCalculatorTest {

    private final PassRateCalculator calculator = new PassRateCalculator();

    private static RunPassCounts counts(long passedTrue, long passedFalse, long unscored, long failed) {
        return new RunPassCounts(UUID.randomUUID(), passedTrue, passedFalse, unscored, failed);
    }

    @Test
    @DisplayName("with a threshold only passed=true counts: 1 passed, 1 below, 1 unscored, 1 failed is 0.25")
    void thresholdCountsOnlyPassedTrue() {
        assertThat(calculator.rate(counts(1, 1, 1, 1), true)).isEqualTo(0.25);
    }

    @Test
    @DisplayName("without a threshold every SUCCESS test case counts: 2 SUCCESS and 1 failed is 2/3")
    void noThresholdCountsEverySuccessRow() {
        assertThat(calculator.rate(counts(0, 0, 2, 1), false)).isCloseTo(2.0 / 3.0, within(1e-12));
    }

    @Test
    @DisplayName("a failed test case counts in the denominator and never as passed, with or without a threshold")
    void failedIsNeverPassed() {
        assertThat(calculator.rate(counts(0, 0, 0, 3), true)).isEqualTo(0.0);
        assertThat(calculator.rate(counts(0, 0, 0, 3), false)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a run with no rows has no rate")
    void emptyRunHasNoRate() {
        assertThat(calculator.rate(counts(0, 0, 0, 0), true)).isNull();
        assertThat(calculator.rate(counts(0, 0, 0, 0), false)).isNull();
    }

    @Test
    @DisplayName("a threshold run where every test case passed has rate 1.0")
    void allPassedIsOne() {
        assertThat(calculator.rate(counts(4, 0, 0, 0), true)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("mean averages the rates, each counting once")
    void meanAveragesRates() {
        assertThat(calculator.mean(List.of(1.0, 0.5))).isEqualTo(0.75);
    }

    @Test
    @DisplayName("mean of no rates is null")
    void meanOfEmptyIsNull() {
        assertThat(calculator.mean(List.of())).isNull();
        assertThat(calculator.mean(null)).isNull();
    }
}
