package com.epam.aidial.evaluation.functional.helper;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * A {@code test_case_eval_summaries} row to insert.
 *
 * <p>Exists because the comparison feature's match key is {@code lower(test_case_name)} + {@code run_index} +
 * {@code turn_index}, so a fixture must be able to vary all three independently — as a positional parameter
 * list that would be a dozen arguments long, most of them irrelevant to any given test. The defaults
 * reproduce the pre-existing single-turn, single-repetition helper exactly, so a test names only the columns
 * it is actually about.
 *
 * <p>A class rather than a record: Java records take no field initializers, so {@code @Builder.Default}
 * cannot be expressed on a record component.
 */
@Getter
@Builder
public class EvalSummaryFixture {

    private final UUID suiteId;
    private final UUID runId;
    private final UUID computationId;
    private final String testCaseName;
    private final long createdAtMs;

    /**
     * Nullable, and read through {@link #getComputedAtMs()} rather than the generated getter: latest-computation
     * resolution orders by {@code computed_at_ms}, so a test seeding two computations must separate them
     * explicitly, while every other test wants it to mirror {@code createdAtMs} — a value {@code @Builder.Default}
     * cannot reference.
     */
    private final Long computedAtMs;

    @Builder.Default
    private final String executionStatus = ExecutionStatus.SUCCESS.name();

    @Builder.Default
    private final long execDurationMs = 100L;

    @Builder.Default
    private final String testCaseDataJson = "{}";

    @Builder.Default
    private final String metricValuesJson = "{}";

    @Builder.Default
    private final int runIndex = 0;

    @Builder.Default
    private final int turnIndex = 0;

    @Builder.Default
    private final int totalTurns = 1;

    public long getComputedAtMs() {
        return computedAtMs != null ? computedAtMs : createdAtMs;
    }
}
