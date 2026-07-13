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
    @DisplayName("puts run and suite ids into the current baggage within the scope")
    void shouldPutRunAndSuiteIdsIntoBaggageWithinScope() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isEqualTo(suiteId.toString());
        }
    }

    @Test
    @DisplayName("removes run and suite ids from the baggage after the scope closes (no leak)")
    void shouldRemoveIdsFromBaggageAfterScopeCloses() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withRunContext(runId, suiteId)) {
            // entries present inside the scope (covered by the previous test)
        }

        Baggage baggage = Baggage.current();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isNull();
        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
    }

    @Test
    @DisplayName("does not throw and adds no entries when both ids are null")
    void shouldNotThrowWhenBothIdsAreNull() {
        assertThatCode(() -> {
                    try (Scope scope = EvalBaggage.withRunContext(null, null)) {
                        Baggage baggage = Baggage.current();
                        assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID))
                                .isNull();
                        assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID))
                                .isNull();
                    }
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("adds only the run id when the suite id is null")
    void shouldAddOnlyRunIdWhenSuiteIdIsNull() {
        UUID runId = UUID.randomUUID();

        try (Scope scope = EvalBaggage.withRunContext(runId, null)) {
            Baggage baggage = Baggage.current();
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_RUN_ID)).isEqualTo(runId.toString());
            assertThat(baggage.getEntryValue(TracingConstants.EVAL_SUITE_ID)).isNull();
        }
    }
}
