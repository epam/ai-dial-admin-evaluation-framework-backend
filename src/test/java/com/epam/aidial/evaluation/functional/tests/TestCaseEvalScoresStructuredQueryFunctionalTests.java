package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end reads of the {@code test_case_eval_scores} queryable entity via the unified Query DSL on
 * real Postgres. One row per test case per computation, enforced by a unique constraint.
 */
@DisplayName("Structured Query -> jOOQ translation (test_case_eval_scores) Tests")
public abstract class TestCaseEvalScoresStructuredQueryFunctionalTests extends BaseFunctionalTest {

    private static final String OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";

    @Autowired
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Test
    @DisplayName("execution_status is selectable/filterable on the entity, same as score/passed")
    void executionStatusIsQueryable() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID successCase = UUID.randomUUID();
        UUID failedCase = UUID.randomUUID();

        testCaseEvalScoreRepository.saveAll(List.of(
                TestCaseEvalScore.builder()
                        .id(UUID.randomUUID())
                        .testSuiteRunId(runId)
                        .testCaseId(successCase)
                        .testCaseName("success-case")
                        .computationId(computationId)
                        .executionStatus(ExecutionStatus.SUCCESS)
                        .score(0.9)
                        .passed(true)
                        .computedAtMs(1_000L)
                        .build(),
                TestCaseEvalScore.builder()
                        .id(UUID.randomUUID())
                        .testSuiteRunId(runId)
                        .testCaseId(failedCase)
                        .testCaseName("failed-case")
                        .computationId(computationId)
                        .executionStatus(ExecutionStatus.FAILED)
                        .score(null)
                        .passed(null)
                        .computedAtMs(1_000L)
                        .build()));

        QueryResultPage page = queryRepository.execute(rowQuery(
                runIdIn(List.of(runId)), List.of(col("test_case_name"), col("execution_status"), col("score"))));

        assertThat(page.rows()).hasSize(2);
        Map<String, Map<String, Object>> byName =
                page.rows().stream().collect(Collectors.toMap(row -> (String) row.get("test_case_name"), row -> row));
        assertThat(byName.get("success-case").get("execution_status")).isEqualTo("SUCCESS");
        assertThat(byName.get("failed-case").get("execution_status")).isEqualTo("FAILED");
        assertThat(byName.get("failed-case").get("score")).isNull();
    }

    @Test
    @DisplayName("a duplicate (run, test case, computation) insert is ignored and the first row stays")
    void duplicateInsertKeepsFirstRow() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        testCaseEvalScoreRepository.saveAll(
                List.of(scoreRow(runId, testCaseId, "case-a", computationId, 0.75, 1_000L)));
        testCaseEvalScoreRepository.saveAll(List.of(scoreRow(runId, testCaseId, "case-a", computationId, 0.1, 2_000L)));

        QueryResultPage page = queryRepository.execute(
                rowQuery(runIdIn(List.of(runId)), List.of(col("test_case_name"), col("score"))));

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("score")).doubleValue()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("resolves the computation_id eq \"latest\" sentinel to the run's latest computation")
    void resolvesLatestSentinel() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("tces-suite-" + UUID.randomUUID())
                .getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        UUID testCaseId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        metaTestDataHelper.createRunMetricSnapshot(runId, older, "Relevancy", OUTPUT_SCHEMA, 1_000L);
        metaTestDataHelper.createRunMetricSnapshot(runId, newer, "Relevancy", OUTPUT_SCHEMA, 2_000L);
        // "latest" is resolved from eval summaries, so each computation needs readable rows — with the
        // same computed_at_ms as its snapshot so the newer computation still wins.
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(older)
                .testCaseId(testCaseId)
                .testCaseName("case-a")
                .execDurationMs(10L)
                .createdAtMs(1_000L)
                .computedAtMs(1_000L)
                .build());
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(newer)
                .testCaseId(testCaseId)
                .testCaseName("case-a")
                .execDurationMs(10L)
                .createdAtMs(1_000L)
                .computedAtMs(2_000L)
                .build());
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(runId, testCaseId, "case-a", older, 0.4, 1_000L),
                scoreRow(runId, testCaseId, "case-a", newer, 0.8, 2_000L)));

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        runIdEq(runId),
                        new ComparisonNode(
                                ComparisonOp.EQ,
                                List.of(new FieldExpr("computation_id"), new ValueExpr(ValueType.STRING, "latest")))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col("computation_id"), col("score"))));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).get("computation_id")).isEqualTo(newer.toString());
        assertThat(((Number) page.rows().get(0).get("score")).doubleValue()).isEqualTo(0.8);
    }

    private static TestCaseEvalScore scoreRow(
            UUID runId, UUID testCaseId, String testCaseName, UUID computationId, double score, long computedAtMs) {
        return TestCaseEvalScore.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName(testCaseName)
                .computationId(computationId)
                .executionStatus(ExecutionStatus.SUCCESS)
                .score(score)
                .passed(true)
                .computedAtMs(computedAtMs)
                .build();
    }

    private static StructuredQuery rowQuery(FilterNode filter, List<OutputColumn> select) {
        return new StructuredQuery(
                "test_case_eval_scores",
                filter,
                QueryMode.ROW,
                false,
                select,
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    private static OutputColumn col(String field) {
        return new OutputColumn(new FieldExpr(field), null);
    }

    private static ComparisonNode runIdEq(UUID runId) {
        return new ComparisonNode(
                ComparisonOp.EQ,
                List.of(new FieldExpr("test_suite_run_id"), new ValueExpr(ValueType.UUID, runId.toString())));
    }

    private static ComparisonNode runIdIn(List<UUID> runIds) {
        return new ComparisonNode(
                ComparisonOp.IN,
                List.of(
                        new FieldExpr("test_suite_run_id"),
                        new ArrayExpr(runIds.stream()
                                .<Expr>map(id -> new ValueExpr(ValueType.UUID, id.toString()))
                                .toList())));
    }
}
