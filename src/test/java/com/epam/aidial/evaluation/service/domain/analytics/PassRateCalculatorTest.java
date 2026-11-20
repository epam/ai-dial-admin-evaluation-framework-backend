package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PassRateCalculator")
class PassRateCalculatorTest {

    private final PassRateCalculator calculator = new PassRateCalculator();

    @Test
    @DisplayName("rate is passed over total: 1 of 2 is 0.5")
    void rateIsPassedOverTotal() {
        assertThat(calculator.rate(1, 2)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("2 of 3 is 2/3")
    void twoOfThree() {
        assertThat(calculator.rate(2, 3)).isCloseTo(2.0 / 3.0, within(1e-12));
    }

    @Test
    @DisplayName("no test case passed gives 0.0")
    void nonePassedIsZero() {
        assertThat(calculator.rate(0, 3)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a run with no test cases has no rate")
    void emptyRunHasNoRate() {
        assertThat(calculator.rate(0, 0)).isNull();
    }

    @Test
    @DisplayName("every test case passed has rate 1.0")
    void allPassedIsOne() {
        assertThat(calculator.rate(4, 4)).isEqualTo(1.0);
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
