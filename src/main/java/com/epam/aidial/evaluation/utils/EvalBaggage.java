package com.epam.aidial.evaluation.utils;

import com.epam.aidial.evaluation.constants.TracingConstants;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.context.Scope;
import java.util.UUID;
import lombok.experimental.UtilityClass;

/**
 * Populates OTel Baggage with the eval run/suite ids for the duration of an execution scope.
 *
 * <p>Mirrors the {@link TraceContextUtils} static-helper pattern. Baggage lives in the OTel API and is
 * independent of the SDK, so entries are added to the context regardless of whether OTel is enabled; the
 * outgoing {@code baggage} header is emitted only when the tracing interceptor's propagator is active
 * (a no-op propagator is used when the SDK is disabled, so no header is written in the default config).
 *
 * <p>Use with try-with-resources alongside {@code span.makeCurrent()} so the entries are cleared when the
 * scope closes and do not leak onto a reused pooled/virtual thread.
 */
@UtilityClass
public class EvalBaggage {

    /**
     * Adds {@code eval.run.id} and {@code eval.suite.id} to the current OTel Baggage and returns the
     * resulting {@link Scope}. Null ids are skipped (no entry added), so the call never throws for
     * missing ids.
     *
     * @param runId the evaluation run id, or {@code null} to skip
     * @param suiteId the evaluation suite id, or {@code null} to skip
     * @return the baggage scope; close it to restore the previous baggage
     */
    public static Scope withRunContext(UUID runId, UUID suiteId) {
        BaggageBuilder builder = Baggage.current().toBuilder();
        if (runId != null) {
            builder.put(TracingConstants.EVAL_RUN_ID, runId.toString());
        }
        if (suiteId != null) {
            builder.put(TracingConstants.EVAL_SUITE_ID, suiteId.toString());
        }
        return builder.build().makeCurrent();
    }
}
