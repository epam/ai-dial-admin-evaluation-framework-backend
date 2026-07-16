package com.epam.aidial.evaluation.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.epam.aidial.evaluation.constants.TracingConstants;
import io.opentelemetry.api.baggage.Baggage;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EvalBaggage}. Baggage lives in the OTel API and is independent of the SDK, so
 * these assertions need no OpenTelemetry SDK — only the default context storage. The helpers build a
 * {@link Baggage} without making it current, so the tests assert directly on the returned value.
 */
@DisplayName("EvalBaggage")
class EvalBaggageTest {

    @Test
    @DisplayName("execution context puts run id, suite id, testcase id, run index and phase=execution into baggage")
    void shouldPutExecutionIdentifiersIntoBaggage() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        int runIndex = 3;

        Baggage baggage = EvalBaggage.withExecutionContext(runId, suiteId, testCaseId, runIndex);

        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isEqualTo(suiteId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isEqualTo(testCaseId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isEqualTo("3");
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE)).isEqualTo(TracingConstants.PHASE_EXECUTION);
    }

    @Test
    @DisplayName("execution context adds no result id or metric declaration name")
    void shouldNotAddMetricOnlyMembersForExecutionContext() {
        UUID runId = UUID.randomUUID();

        Baggage baggage = EvalBaggage.withExecutionContext(runId, UUID.randomUUID(), UUID.randomUUID(), 0);

        assertThat(baggage.getEntryValue(TracingConstants.RESULT_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                .isNull();
    }

    @Test
    @DisplayName(
            "metric context puts the shared ids plus phase=metric-evaluation, result id and metric declaration name")
    void shouldPutMetricIdentifiersIntoBaggage() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        int runIndex = 1;
        String metricName = "answer-correctness";

        Baggage baggage = EvalBaggage.withMetricContext(runId, suiteId, testCaseId, runIndex, resultId, metricName);

        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isEqualTo(suiteId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isEqualTo(testCaseId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isEqualTo("1");
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE))
                .isEqualTo(TracingConstants.PHASE_METRIC_EVALUATION);
        assertThat(baggage.getEntryValue(TracingConstants.RESULT_ID)).isEqualTo(resultId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                .isEqualTo(metricName);
    }

    @Test
    @DisplayName("does not mutate the current baggage (builds a detached copy the caller must make current)")
    void shouldNotMutateCurrentBaggage() {
        UUID runId = UUID.randomUUID();

        EvalBaggage.withMetricContext(runId, UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID(), "m");

        Baggage current = Baggage.current();
        assertThat(current.getEntryValue(TracingConstants.EVAL_RUN_ID)).isNull();
        assertThat(current.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
        assertThat(current.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
        assertThat(current.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
        assertThat(current.getEntryValue(TracingConstants.EVAL_PHASE)).isNull();
        assertThat(current.getEntryValue(TracingConstants.RESULT_ID)).isNull();
        assertThat(current.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                .isNull();
    }

    @Test
    @DisplayName("does not throw and adds no id entries when all values are null")
    void shouldNotThrowWhenAllValuesAreNull() {
        assertThatCode(() -> {
                    Baggage baggage = EvalBaggage.withMetricContext(null, null, null, null, null, null);
                    assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID))
                            .isNull();
                    assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID))
                            .isNull();
                    assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID))
                            .isNull();
                    assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX))
                            .isNull();
                    assertThat(baggage.getEntryValue(TracingConstants.RESULT_ID))
                            .isNull();
                    assertThat(baggage.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                            .isNull();
                    // phase is always set (a constant), independent of the nullable ids
                    assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE))
                            .isEqualTo(TracingConstants.PHASE_METRIC_EVALUATION);
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adds only the run id (plus phase) when the other values are null")
    void shouldAddOnlyRunIdWhenOtherValuesAreNull() {
        UUID runId = UUID.randomUUID();

        Baggage baggage = EvalBaggage.withExecutionContext(runId, null, null, null);

        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE)).isEqualTo(TracingConstants.PHASE_EXECUTION);
    }
}
