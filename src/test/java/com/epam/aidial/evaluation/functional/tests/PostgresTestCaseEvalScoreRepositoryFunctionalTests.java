package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code TestCaseEvalScoreRepository} has no finder of its own (unlike
 * {@code TestCaseMetricScoreAggregatedRepository}) — every read goes through the {@code eval_summaries}
 * join or the {@code test_case_eval_scores} Query DSL entity (see
 * {@code TestCaseEvalScoresStructuredQueryFunctionalTests}), so writes here are verified by reading them
 * back through that entity rather than adding a test-only repository method.
 */
@DisplayName("PostgresTestCaseEvalScoreRepository tests")
public abstract class PostgresTestCaseEvalScoreRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private TestCaseEvalScoreRepository repository;

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Test
    @DisplayName("saveAll inserts a row with all 4 new columns populated")
    void saveAllInsertsWithRunCaseContext() {
        UUID evalSummaryId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        repository.saveAll(List.of(TestCaseEvalScore.builder()
                .evalSummaryId(evalSummaryId)
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName("case-a")
                .computationId(computationId)
                .score(0.5)
                .passed(true)
                .computedAtMs(1_000L)
                .build()));

        Map<String, Object> row = findByRunId(runId);
        assertThat(row.get("test_case_name")).isEqualTo("case-a");
        assertThat(row.get("computation_id")).isEqualTo(computationId.toString());
        assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(0.5);
        assertThat(row.get("passed")).isEqualTo(true);
    }

    @Test
    @DisplayName("saveAll upserts score/passed/computed_at_ms on conflict, correcting a stale value")
    void saveAllUpsertsOnConflict() {
        UUID evalSummaryId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        repository.saveAll(List.of(scoreRow(evalSummaryId, runId, testCaseId, computationId, 0.0, false, 1_000L)));
        // Simulates a later flush correcting an earlier, stale score for the same raw row.
        repository.saveAll(List.of(scoreRow(evalSummaryId, runId, testCaseId, computationId, 0.9, true, 2_000L)));

        Map<String, Object> row = findByRunId(runId);
        assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(0.9);
        assertThat(row.get("passed")).isEqualTo(true);
    }

    private Map<String, Object> findByRunId(UUID runId) {
        StructuredQuery query = new StructuredQuery(
                "test_case_eval_scores",
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("test_suite_run_id"), new ValueExpr(ValueType.UUID, runId.toString()))),
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("test_case_name"), null),
                        new OutputColumn(new FieldExpr("computation_id"), null),
                        new OutputColumn(new FieldExpr("score"), null),
                        new OutputColumn(new FieldExpr("passed"), null)),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);
        assertThat(page.rows()).hasSize(1);
        return page.rows().get(0);
    }

    private static TestCaseEvalScore scoreRow(
            UUID evalSummaryId,
            UUID runId,
            UUID testCaseId,
            UUID computationId,
            double score,
            boolean passed,
            long computedAtMs) {
        return TestCaseEvalScore.builder()
                .evalSummaryId(evalSummaryId)
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName("case-a")
                .computationId(computationId)
                .score(score)
                .passed(passed)
                .computedAtMs(computedAtMs)
                .build();
    }
}
