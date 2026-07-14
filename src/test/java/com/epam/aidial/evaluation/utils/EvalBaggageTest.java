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
    @DisplayName("puts run id, suite id, testcase id and run index into the current baggage within the scope")
    void shouldPutAllIdentifiersIntoBaggageWithinScope() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        int runIndex = 3;

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId, testCaseId, runIndex)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isEqualTo(suiteId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isEqualTo(testCaseId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isEqualTo("3");
        }
    }

    @Test
    @DisplayName("removes all identifiers from the baggage after the scope closes (no leak)")
    void shouldRemoveIdsFromBaggageAfterScopeCloses() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId, testCaseId, 0)) {
            // entries present inside the scope (covered by the previous test)
        }

        Baggage baggage = Baggage.current();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
    }

    @Test
    @DisplayName("does not throw and adds no entries when all values are null")
    void shouldNotThrowWhenAllValuesAreNull() {
        assertThatCode(() -> {
                    try (Scope scope = EvalBaggage.withRunContext(null, null, null, null)) {
                        Baggage baggage = Baggage.current();
                        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID))
                                .isNull();
                        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID))
                                .isNull();
                        assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID))
                                .isNull();
                        assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX))
                                .isNull();
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adds only the run id when the other values are null")
    void shouldAddOnlyRunIdWhenOtherValuesAreNull() {
        UUID runId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withRunContext(runId, null, null, null)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.TESTCASE_ID)).isNull();
            assertThat(baggage.getEntryValue(TracingConstants.RUN_INDEX)).isNull();
        }
    }
}
