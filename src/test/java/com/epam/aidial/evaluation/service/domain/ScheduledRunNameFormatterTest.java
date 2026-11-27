package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.RunnerValidationConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScheduledRunNameFormatter")
class ScheduledRunNameFormatterTest {

    private static final int MAX = RunnerValidationConstants.MAX_TEST_RUN_NAME_LENGTH;

    private final ScheduledRunNameFormatter formatter = new ScheduledRunNameFormatter();

    @Test
    @DisplayName("shouldAppendTheSequenceValueToTheBaseName")
    void shouldAppendSequenceValue() {
        assertThat(formatter.format("Nightly", 7)).isEqualTo("Nightly #7");
    }

    @Test
    @DisplayName("shouldGiveDifferentNamesForDifferentSequenceValues")
    void shouldGiveDifferentNamesForDifferentSequenceValues() {
        assertThat(formatter.format("Nightly", 7)).isNotEqualTo(formatter.format("Nightly", 8));
    }

    @Test
    @DisplayName("shouldKeepABaseNameThatExactlyFitsUntouched")
    void shouldKeepBaseThatExactlyFits() {
        final String base = "a".repeat(MAX - " #7".length());

        final String name = formatter.format(base, 7);

        assertThat(name).hasSize(MAX).startsWith(base).endsWith(" #7");
    }

    @Test
    @DisplayName("shouldTruncateAnOverLongBaseSoTheWholeNameStillEndsWithTheSuffixAndFitsTheColumn")
    void shouldTruncateOverLongBase() {
        final String name = formatter.format("b".repeat(MAX + 40), 123_456);

        assertThat(name).hasSize(MAX).endsWith(" #123456").startsWith("bbb");
    }
}
