package com.epam.aidial.evaluation.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code metric_score_results} entity (computed Phase-3 metric-score
 * statistics). The schema is derived once from the generated jOOQ {@code METRIC_SCORE_RESULT} table —
 * all columns are plain ({@code id}/{@code test_suite_run_id}/{@code computation_id} as {@code uuid},
 * {@code metric_score_name}/{@code metric_name} as {@code string}, {@code value} as {@code decimal}) —
 * so the entity is not complex and has no detailed schema. {@code computation_id} is supplied
 * explicitly by the caller; resolving {@code latest} stays a server-side concern (the DSL is generic
 * and never sees the {@code latest} sentinel).
 */
@Component
@LogExecution
public class MetricScoreResultSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "metric_score_results";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public MetricScoreResultSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        this.baseSchema = schemaResolver.resolve(METRIC_SCORE_RESULT);
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
