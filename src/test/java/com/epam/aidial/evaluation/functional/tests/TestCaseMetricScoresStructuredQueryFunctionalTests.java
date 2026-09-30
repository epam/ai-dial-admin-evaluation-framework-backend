package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseMetricScoreAggregatedRepository;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end read of the {@code test_case_metric_scores} queryable entity via the unified Query DSL on
 * real Postgres: a flattened {@code metric_scores::<name>::<stat>} field resolves to the corresponding
 * value inside the JSONB column, the same mechanism used for {@code eval_summaries.metric_values}.
 */
@DisplayName("Structured Query -> jOOQ translation (test_case_metric_scores) Tests")
public abstract class TestCaseMetricScoresStructuredQueryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private TestCaseMetricScoreAggregatedRepository repository;

    @Autowired
    private StructuredQueryService structuredQueryService;

    @Test
    @DisplayName("a flattened metric_scores field resolves to the value inside the JSONB column")
    void flattenedMetricScoresFieldIsQueryable() {
        UUID runId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        repository.saveAll(List.of(TestCaseMetricScoreAggregated.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .computationId(computationId)
                .metricScores("{\"MetricA.score\":{\"avg\":0.75,\"min\":0.5,\"max\":1.0,\"count\":4}}")
                .createdAtMs(1_000L)
                .computedAtMs(1_000L)
                .build()));

        StructuredQuery query = new StructuredQuery(
                "test_case_metric_scores",
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("test_case_id"), new ValueExpr(ValueType.UUID, testCaseId.toString()))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("metric_scores::MetricA.score::avg"), "avg_score")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = structuredQueryService.execute(query);

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("avg_score")).doubleValue()).isEqualTo(0.75);
    }
}
