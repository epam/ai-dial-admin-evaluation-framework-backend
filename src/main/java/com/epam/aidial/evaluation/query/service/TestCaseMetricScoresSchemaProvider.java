package com.epam.aidial.evaluation.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_METRIC_SCORES_AGGREGATED;

import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code test_case_metric_scores} entity (per-test-case aggregated
 * metric statistics). The schema is derived once from the generated jOOQ
 * {@code TEST_CASE_METRIC_SCORES_AGGREGATED} table — all columns are plain ({@code id}/
 * {@code test_suite_run_id}/{@code test_case_id}/{@code computation_id} as {@code uuid},
 * {@code metric_scores} as an opaque {@code object}, {@code created_at_ms}/{@code computed_at_ms} as
 * {@code long}) — so the entity is not complex and has no detailed schema. The {@code metric_scores}
 * JSONB column's per-metric {@code avg}/{@code min}/{@code max}/{@code count} values are still directly
 * queryable via the flattened {@code metric_scores::<name>::<stat>} field family, resolved by
 * {@code JsonbFieldResolver} at translation time rather than advertised here — mirroring
 * {@code MetricScoreResultSchemaProvider}.
 */
@Component
@LogExecution
public class TestCaseMetricScoresSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "test_case_metric_scores";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public TestCaseMetricScoresSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        this.baseSchema = schemaResolver.resolve(TEST_CASE_METRIC_SCORES_AGGREGATED);
    }

    @Override
    public QueryEntityDto descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<QuerySchemaFieldDto> baseSchema() {
        return baseSchema;
    }
}
