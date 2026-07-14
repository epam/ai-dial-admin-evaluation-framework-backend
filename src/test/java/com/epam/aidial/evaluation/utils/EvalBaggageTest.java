package com.epam.aidial.evaluation.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.epam.aidial.evaluation.constants.TracingConstants;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EvalBaggage}. Baggage lives in the OTel API and is independent of the SDK, so
 * these assertions need no OpenTelemetry SDK — only the default context storage.
 */
@DisplayName("EvalBaggage")
class EvalBaggageTest {

    @Test
    @DisplayName("execution context puts run id, suite id, testcase id, run index and phase=execution into baggage")
    void shouldPutExecutionIdentifiersIntoBaggageWithinScope() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        int runIndex = 3;

        try (Scope scope = EvalBaggage.withExecutionContext(runId, suiteId, testCaseId, runIndex)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isEqualTo(suiteId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isEqualTo(testCaseId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isEqualTo("3");
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE)).isEqualTo(TracingConstants.PHASE_EXECUTION);
        }
    }

    @Test
    @DisplayName("execution context adds no result id or metric declaration name")
    void shouldNotAddMetricOnlyMembersForExecutionContext() {
        UUID runId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withExecutionContext(runId, UUID.randomUUID(), UUID.randomUUID(), 0)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.RESULT_ID)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                    .isNull();
        }
    }

    @Test
    @DisplayName(
            "metric context puts the shared ids plus phase=metric-evaluation, result id and metric declaration name")
    void shouldPutMetricIdentifiersIntoBaggageWithinScope() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        int runIndex = 1;
        String metricName = "answer-correctness";

        try (Scope scope = EvalBaggage.withMetricContext(runId, suiteId, testCaseId, runIndex, resultId, metricName)) {
            Baggage baggage = Baggage.current();
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
    }

    @Test
    @DisplayName("removes all identifiers from the baggage after the scope closes (no leak)")
    void shouldRemoveIdsFromBaggageAfterScopeCloses() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withMetricContext(runId, suiteId, testCaseId, 0, UUID.randomUUID(), "m")) {
            // entries present inside the scope (covered by the previous test)
        }

        Baggage baggage = Baggage.current();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.RESULT_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.METRIC_DECLARATION_NAME))
                .isNull();
    }

    @Test
    @DisplayName("does not throw and adds no id entries when all values are null")
    void shouldNotThrowWhenAllValuesAreNull() {
        assertThatCode(() -> {
                    try (Scope scope = EvalBaggage.withMetricContext(null, null, null, null, null, null)) {
                        Baggage baggage = Baggage.current();
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
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adds only the run id (plus phase) when the other values are null")
    void shouldAddOnlyRunIdWhenOtherValuesAreNull() {
        UUID runId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withExecutionContext(runId, null, null, null)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_PHASE)).isEqualTo(TracingConstants.PHASE_EXECUTION);
        }
    }
}
