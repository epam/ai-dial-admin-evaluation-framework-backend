package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseMetricScoreAggregatedRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.analytics.RunDeletionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a run (or a suite) tombstones its analytics rows. Visibility is asserted through the production
 * read paths — direct repository reads and every Query DSL entity over a run-scoped analytics table — never
 * through the raw-table back-door helpers, which deliberately still see tombstoned rows. A live run seeded
 * alongside is the control: it must stay fully visible.
 */
@DisplayName("RunDeletionFunctionalTests")
public abstract class RunDeletionFunctionalTests {

    private static final String RUN_CONFIG = "{\"numberOfRuns\":1}";

    private static final List<String> RUN_SCOPED_QUERY_ENTITIES =
            List.of("eval_summaries", "test_case_eval_scores", "test_case_metric_scores", "metric_score_results");

    @Autowired
    private TestSuiteRunService testSuiteRunService;

    @Autowired
    private TestSuiteService testSuiteService;

    @Autowired
    private RunDeletionService runDeletionService;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseRunResultRepository testCaseRunResultRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @Autowired
    private TestCaseMetricScoreAggregatedRepository metricScoreAggregatedRepository;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    @Autowired
    private StructuredQueryExecutor queryExecutor;

    @Test
    @DisplayName("3.5: Deleting a terminal run excludes its rows from every analytics read path, and only its rows")
    void deleteRunExcludesAnalyticsRowsFromAllReadPaths() {
        var suite = createSuite();
        var deleted = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, RUN_CONFIG);
        var live = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, RUN_CONFIG);
        var deletedRows = seedAnalytics(deleted.getId(), suite.getId());
        var liveRows = seedAnalytics(live.getId(), suite.getId());
        assertVisible(deletedRows);

        testSuiteRunService.deleteRun(deleted.getId());

        assertHidden(deletedRows);
        assertVisible(liveRows);
    }

    @Test
    @DisplayName("3.6: Deleting a suite excludes every one of its runs' rows from every analytics read path")
    void deleteSuiteExcludesAllRunAnalyticsRowsFromReadPaths() {
        var suite = createSuite();
        var run1 = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, RUN_CONFIG);
        var run2 = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.FAILED, RUN_CONFIG);
        var rows1 = seedAnalytics(run1.getId(), suite.getId());
        var rows2 = seedAnalytics(run2.getId(), suite.getId());
        assertVisible(rows1);
        assertVisible(rows2);

        testSuiteService.delete(suite.getId());

        assertHidden(rows1);
        assertHidden(rows2);
    }

    @Test
    @DisplayName("3.7: Re-writing an existing tombstone is a no-op and the run stays excluded")
    void tombstoneWriteIsIdempotent() {
        var suite = createSuite();
        var run = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.COMPLETED, RUN_CONFIG);
        var rows = seedAnalytics(run.getId(), suite.getId());

        runDeletionService.markDeleted(run.getId());
        runDeletionService.markDeleted(run.getId());

        assertHidden(rows);
    }

    /** Ids of the rows seeded for one run, so each read path can be probed for exactly those rows. */
    private record SeededRows(UUID runId, UUID computationId, UUID runResultId, UUID evalSummaryId) {}

    private TestSuite createSuite() {
        var dataset = metaTestDataHelper.createDataset("deletion-dataset-" + UUID.randomUUID());
        return metaTestDataHelper.createTestSuite("deletion-suite-" + UUID.randomUUID(), dataset.getId());
    }

    private SeededRows seedAnalytics(UUID runId, UUID suiteId) {
        final UUID computationId = UUID.randomUUID();
        final UUID testCaseId = UUID.randomUUID();
        final long now = System.currentTimeMillis();

        final UUID runResultId =
                analyticsTestDataHelper.createTestRunResult(runId, suiteId, testCaseId, "test-case-1", "{}", "{}", now);
        final UUID evalSummaryId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "test-case-1", "SUCCESS", 100L, now);
        testCaseEvalScoreRepository.saveAll(List.of(TestCaseEvalScore.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .testCaseName("test-case-1")
                .computationId(computationId)
                .executionStatus(ExecutionStatus.SUCCESS)
                .score(0.5)
                .passed(true)
                .computedAtMs(now)
                .build()));
        metricScoreAggregatedRepository.saveAll(List.of(TestCaseMetricScoreAggregated.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testCaseId(testCaseId)
                .computationId(computationId)
                .metricScores("{}")
                .createdAtMs(now)
                .computedAtMs(now)
                .build()));
        metricScoreResultRepository.saveAll(List.of(MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .metricScoreName("score")
                .metricName("Accuracy")
                .value(0.5)
                .computedAtMs(now)
                .build()));
        return new SeededRows(runId, computationId, runResultId, evalSummaryId);
    }

    private void assertVisible(SeededRows rows) {
        assertThat(directReadCounts(rows)).as("direct repository reads").containsOnly(1L);
        for (final String entity : RUN_SCOPED_QUERY_ENTITIES) {
            assertThat(queryRowCount(entity, rows.runId()))
                    .as("Query DSL entity %s", entity)
                    .isEqualTo(1);
        }
    }

    private void assertHidden(SeededRows rows) {
        assertThat(directReadCounts(rows)).as("direct repository reads").containsOnly(0L);
        for (final String entity : RUN_SCOPED_QUERY_ENTITIES) {
            assertThat(queryRowCount(entity, rows.runId()))
                    .as("Query DSL entity %s", entity)
                    .isZero();
        }
    }

    /** One count per directly-read table; each repository method is the one production code uses. */
    private List<Long> directReadCounts(SeededRows rows) {
        return List.of(
                testCaseRunResultRepository.findById(rows.runResultId()).isPresent() ? 1L : 0L,
                evalSummaryRepository.findById(rows.evalSummaryId()).isPresent() ? 1L : 0L,
                evalSummaryRepository.existsByRunIdAndComputationId(rows.runId(), rows.computationId()) ? 1L : 0L,
                evalSummaryRepository.findLatestComputationId(rows.runId()).isPresent() ? 1L : 0L,
                (long) metricScoreAggregatedRepository
                        .findByRunIdAndComputationId(rows.runId(), rows.computationId())
                        .size(),
                (long) metricScoreResultRepository
                        .findByRunAndComputation(rows.runId(), rows.computationId())
                        .size());
    }

    private int queryRowCount(String entity, UUID runId) {
        final StructuredQuery query = new StructuredQuery(
                entity,
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("test_suite_run_id"), new ValueExpr(ValueType.UUID, runId.toString()))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("test_suite_run_id"), null)),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
        return queryExecutor.execute(query).rows().size();
    }
}
