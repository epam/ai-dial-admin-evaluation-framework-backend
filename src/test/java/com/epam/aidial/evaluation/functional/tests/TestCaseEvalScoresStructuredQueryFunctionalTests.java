package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
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
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.SortDir;
import com.epam.aidial.evaluation.query.model.SortItem;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.SubqueryExpr;
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
    private RunDeletionRepository runDeletionRepository;

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

    @Test
    @DisplayName("a multi-run literal list resolves each run's own latest computation")
    void multiRunLiteralListResolvesEachRunsLatest() {
        UUID suiteId = UUID.randomUUID();
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        UUID olderA = UUID.randomUUID();
        UUID newerA = UUID.randomUUID();
        UUID onlyB = UUID.randomUUID();
        seedSummary(suiteId, runA, olderA, 1_000L, 1_000L);
        seedSummary(suiteId, runA, newerA, 1_000L, 2_000L);
        seedSummary(suiteId, runB, onlyB, 1_500L, 1_500L);
        UUID caseId = UUID.randomUUID();
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(runA, caseId, "case", olderA, 0.1, 1_000L),
                scoreRow(runA, caseId, "case", newerA, 0.2, 2_000L),
                scoreRow(runB, caseId, "case", onlyB, 0.3, 1_500L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(runA, runB)), latestEq())),
                List.of(col("test_suite_run_id"), col("computation_id"))));

        assertThat(page.rows())
                .extracting(row -> row.get("test_suite_run_id") + ":" + row.get("computation_id"))
                .containsExactlyInAnyOrder(runA + ":" + newerA, runB + ":" + onlyB);
    }

    @Test
    @DisplayName("a recomputed run is counted once in a grouped aggregate against its latest computation")
    void recomputedRunIsCountedOnceInGroupedAggregate() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        seedSummary(suiteId, runId, older, 1_000L, 1_000L);
        seedSummary(suiteId, runId, newer, 1_000L, 2_000L);
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(runId, UUID.randomUUID(), "a", older, 0.1, 1_000L),
                scoreRow(runId, UUID.randomUUID(), "b", older, 0.1, 1_000L),
                scoreRow(runId, UUID.randomUUID(), "a", newer, 0.9, 2_000L),
                scoreRow(runId, UUID.randomUUID(), "b", newer, 0.9, 2_000L)));

        QueryResultPage page = queryRepository.execute(
                countPerRunQuery(new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(runId)), latestEq()))));

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("n")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("computations sharing computed_at_ms resolve to the smaller computation_id")
    void sameComputedAtTiePicksSmallerComputationId() {
        UUID suiteId = UUID.randomUUID();
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID larger = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        seedSummary(suiteId, runA, larger, 1_000L, 5_000L);
        seedSummary(suiteId, runA, smaller, 1_000L, 5_000L);
        seedSummary(suiteId, runB, UUID.randomUUID(), 1_000L, 1_000L);
        UUID caseId = UUID.randomUUID();
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(runA, caseId, "case", larger, 0.1, 5_000L),
                scoreRow(runA, caseId, "case", smaller, 0.2, 5_000L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(runA, runB)), latestEq())),
                List.of(col("computation_id"))));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).get("computation_id")).isEqualTo(smaller.toString());
    }

    @Test
    @DisplayName("a run whose latest computation has no score rows returns no rows, with no fallback")
    void latestWithoutScoreRowsDoesNotFallBack() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        seedSummary(suiteId, runId, older, 1_000L, 1_000L);
        seedSummary(suiteId, runId, newer, 1_000L, 2_000L);
        testCaseEvalScoreRepository.saveAll(List.of(scoreRow(runId, UUID.randomUUID(), "case", older, 0.5, 1_000L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(runId)), latestEq())),
                List.of(col("computation_id"))));

        assertThat(page.rows()).isEmpty();
    }

    @Test
    @DisplayName("a tombstoned run is excluded while the other run's latest rows are returned")
    void tombstonedRunIsExcluded() {
        UUID suiteId = UUID.randomUUID();
        UUID deletedRun = UUID.randomUUID();
        UUID liveRun = UUID.randomUUID();
        UUID deletedComputation = UUID.randomUUID();
        UUID liveComputation = UUID.randomUUID();
        seedSummary(suiteId, deletedRun, deletedComputation, 1_000L, 1_000L);
        seedSummary(suiteId, liveRun, liveComputation, 1_000L, 1_000L);
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(deletedRun, UUID.randomUUID(), "case", deletedComputation, 0.5, 1_000L),
                scoreRow(liveRun, UUID.randomUUID(), "case", liveComputation, 0.5, 1_000L)));
        runDeletionRepository.insert(deletedRun, 9_000L);

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(deletedRun, liveRun)), latestEq())),
                List.of(col("test_suite_run_id"))));

        assertThat(page.rows()).extracting(row -> row.get("test_suite_run_id")).containsExactly(liveRun.toString());
    }

    @Test
    @DisplayName("an empty-select row query projects the entity fields and not is_latest_computation")
    void emptySelectDoesNotContainLatestComputationField() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        seedSummary(suiteId, runId, computationId, 1_000L, 1_000L);
        testCaseEvalScoreRepository.saveAll(
                List.of(scoreRow(runId, UUID.randomUUID(), "case", computationId, 0.5, 1_000L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdIn(List.of(runId)), latestEq())), null));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).keySet())
                .contains("test_suite_run_id", "computation_id", "score")
                .doesNotContain("is_latest_computation");
    }

    @Test
    @DisplayName("last-N-runs dashboard: counts per run, execution status and passed use each run's latest computation")
    void dashboardCountsRecentRunsAgainstEachRunsLatestComputation() {
        UUID suiteId = UUID.randomUUID();
        UUID oldestRun = UUID.randomUUID();
        UUID middleRun = UUID.randomUUID();
        UUID newestRun = UUID.randomUUID();
        UUID oldestComputation = UUID.randomUUID();
        UUID middleStale = UUID.randomUUID();
        UUID middleLatest = UUID.randomUUID();
        UUID newestComputation = UUID.randomUUID();
        seedSummary(suiteId, oldestRun, oldestComputation, 1_000L, 1_000L);
        seedSummary(suiteId, middleRun, middleStale, 2_000L, 2_000L);
        seedSummary(suiteId, middleRun, middleLatest, 2_000L, 2_500L);
        seedSummary(suiteId, newestRun, newestComputation, 3_000L, 3_000L);
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(oldestRun, UUID.randomUUID(), "o", oldestComputation, 0.9, 1_000L),
                scoreRow(middleRun, UUID.randomUUID(), "stale-1", middleStale, 0.9, 2_000L),
                scoreRow(middleRun, UUID.randomUUID(), "stale-2", middleStale, 0.9, 2_000L),
                scoreRow(middleRun, UUID.randomUUID(), "stale-3", middleStale, 0.9, 2_000L),
                scoreRow(middleRun, UUID.randomUUID(), "m-pass", middleLatest, 0.9, 2_500L),
                scoreRow(
                        middleRun,
                        UUID.randomUUID(),
                        "m-fail",
                        middleLatest,
                        0.1,
                        2_500L,
                        ExecutionStatus.SUCCESS,
                        false),
                scoreRow(
                        middleRun,
                        UUID.randomUUID(),
                        "m-err",
                        middleLatest,
                        null,
                        2_500L,
                        ExecutionStatus.FAILED,
                        null),
                scoreRow(newestRun, UUID.randomUUID(), "n-1", newestComputation, 0.8, 3_000L),
                scoreRow(newestRun, UUID.randomUUID(), "n-2", newestComputation, 0.7, 3_000L)));

        QueryResultPage page = queryRepository.execute(dashboardQuery(suiteId, 2));

        assertThat(countsByRunStatusPassed(page))
                .containsOnly(
                        Map.entry(middleRun + "|SUCCESS|true", 1L),
                        Map.entry(middleRun + "|SUCCESS|false", 1L),
                        Map.entry(middleRun + "|FAILED|null", 1L),
                        Map.entry(newestRun + "|SUCCESS|true", 2L));
    }

    @Test
    @DisplayName("a single pinned run with latest keeps resolving to its latest computation only")
    void singlePinnedRunKeepsLatestOnly() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        seedSummary(suiteId, runId, older, 1_000L, 1_000L);
        seedSummary(suiteId, runId, newer, 1_000L, 2_000L);
        testCaseEvalScoreRepository.saveAll(List.of(
                scoreRow(runId, UUID.randomUUID(), "a", older, 0.1, 1_000L),
                scoreRow(runId, UUID.randomUUID(), "a", newer, 0.9, 2_000L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdEq(runId), latestEq())), List.of(col("computation_id"))));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).get("computation_id")).isEqualTo(newer.toString());
    }

    @Test
    @DisplayName("a pinned run with no eval summaries yields an empty result rather than a 400")
    void pinnedRunWithoutSummariesReturnsNoRows() {
        UUID runId = UUID.randomUUID();
        testCaseEvalScoreRepository.saveAll(
                List.of(scoreRow(runId, UUID.randomUUID(), "case", UUID.randomUUID(), 0.5, 1_000L)));

        QueryResultPage page = queryRepository.execute(rowQuery(
                new LogicalNode(LogicalOp.AND, List.of(runIdEq(runId), latestEq())), List.of(col("computation_id"))));

        assertThat(page.rows()).isEmpty();
    }

    private void seedSummary(UUID suiteId, UUID runId, UUID computationId, long createdAtMs, long computedAtMs) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case")
                .execDurationMs(10L)
                .createdAtMs(createdAtMs)
                .computedAtMs(computedAtMs)
                .build());
    }

    private static ComparisonNode latestEq() {
        return new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("computation_id"), new ValueExpr(ValueType.STRING, "latest")));
    }

    private static StructuredQuery countPerRunQuery(FilterNode filter) {
        return new StructuredQuery(
                "test_case_eval_scores",
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(col("test_suite_run_id"), new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("test_suite_run_id"),
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    /** Counts per (run, execution_status, passed) for the suite's last {@code lastRuns} runs, latest computation. */
    private static StructuredQuery dashboardQuery(UUID suiteId, int lastRuns) {
        StructuredQuery lastRunsSubquery = new StructuredQuery(
                "eval_summaries",
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("test_suite_id"), new ValueExpr(ValueType.UUID, suiteId.toString()))),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        col("test_suite_run_id"),
                        new OutputColumn(new FnExpr("max", false, List.of(new FieldExpr("created_at_ms"))), "recency")),
                List.of("test_suite_run_id"),
                null,
                List.of(new SortItem("recency", SortDir.DESC, null)),
                new OffsetPage(0, lastRuns, false));
        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(new FieldExpr("test_suite_run_id"), new SubqueryExpr(lastRunsSubquery))),
                        latestEq()));
        return new StructuredQuery(
                "test_case_eval_scores",
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        col("test_suite_run_id"),
                        col("execution_status"),
                        col("passed"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "n")),
                List.of("test_suite_run_id", "execution_status", "passed"),
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    private static Map<String, Long> countsByRunStatusPassed(QueryResultPage page) {
        return page.rows().stream()
                .collect(Collectors.toMap(
                        row -> row.get("test_suite_run_id") + "|" + row.get("execution_status") + "|"
                                + row.get("passed"),
                        row -> ((Number) row.get("n")).longValue()));
    }

    private static TestCaseEvalScore scoreRow(
            UUID runId, UUID testCaseId, String testCaseName, UUID computationId, double score, long computedAtMs) {
        return scoreRow(
                runId, testCaseId, testCaseName, computationId, score, computedAtMs, ExecutionStatus.SUCCESS, true);
    }

    private static TestCaseEvalScore scoreRow(
            UUID runId,
            UUID testCaseId,
            String testCaseName,
            UUID computationId,
            Double score,
            long computedAtMs,
            ExecutionStatus status,
            Boolean passed) {
        return TestCaseEvalScore.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName(testCaseName)
                .computationId(computationId)
                .executionStatus(status)
                .score(score)
                .passed(passed)
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
