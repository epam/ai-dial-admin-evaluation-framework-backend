package com.epam.aidial.evaluation.query.service;

import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.query.service.repository.PostgresTestCaseEvalScoreEntityResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code test_case_eval_scores} entity (one row per test case per
 * computation, deduplicated — see {@link PostgresTestCaseEvalScoreEntityResolver}). All columns are
 * plain scalars (no JSONB), so the entity is not complex and has no detailed schema, mirroring
 * {@code TestCaseMetricScoresSchemaProvider}. Reads the resolver's static {@code SCORES} table constant
 * directly rather than injecting the (vendor-gated) resolver bean, so schema discovery stays independent
 * of which analytics vendor is configured.
 */
@Component
@LogExecution
public class TestCaseEvalScoresSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "test_case_eval_scores";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public TestCaseEvalScoresSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        this.baseSchema = schemaResolver.resolve(PostgresTestCaseEvalScoreEntityResolver.SCORES);
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
