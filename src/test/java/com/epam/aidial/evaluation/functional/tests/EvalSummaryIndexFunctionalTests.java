package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Postgres-only companion to {@link EvalSummaryFunctionalTests}: asserts the physical index that backs
 * latest-computation resolution. It lives in its own suite because it reads the {@code pg_indexes} catalog
 * view, which has no ClickHouse equivalent (ClickHouse resolves the same access pattern through the
 * table's {@code ORDER BY} key, not a secondary index), so only the Postgres entry point attaches it.
 */
public abstract class EvalSummaryIndexFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Test
    @DisplayName("The latest-computation resolution index exists on test_case_eval_summaries")
    void resolutionIndexExists() {
        var indexDefinition = analyticsTestDataHelper.findIndexDefinition(
                "test_case_eval_summaries", "idx_eval_summaries_run_computed_at");

        assertThat(indexDefinition).isPresent();
        // Contains, not equals: Postgres renders indexdef schema-qualified and with USING btree.
        assertThat(indexDefinition.get()).contains("(test_suite_run_id, computed_at_ms DESC, computation_id)");
    }
}
