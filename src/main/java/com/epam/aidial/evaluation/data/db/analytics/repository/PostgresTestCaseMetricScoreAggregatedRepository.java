package com.epam.aidial.evaluation.data.db.analytics.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_METRIC_SCORES_AGGREGATED;
import static org.jooq.impl.DSL.excluded;

import com.epam.aidial.evaluation.data.db.analytics.mapper.TestCaseMetricScoreAggregatedRecordMapper;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseMetricScoreAggregatedRepository implements TestCaseMetricScoreAggregatedRepository {

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final TestCaseMetricScoreAggregatedRecordMapper recordMapper;

    @Override
    public void saveAll(List<TestCaseMetricScoreAggregated> aggregates) {
        if (aggregates == null || aggregates.isEmpty()) {
            return;
        }
        List<Query> queries = aggregates.stream()
                .map(a -> (Query) dsl.insertInto(TEST_CASE_METRIC_SCORES_AGGREGATED)
                        .set(TEST_CASE_METRIC_SCORES_AGGREGATED.ID, a.getId().toString())
                        .set(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_SUITE_RUN_ID,
                                a.getTestSuiteRunId().toString())
                        .set(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_CASE_ID,
                                a.getTestCaseId().toString())
                        .set(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTATION_ID,
                                a.getComputationId().toString())
                        .set(TEST_CASE_METRIC_SCORES_AGGREGATED.METRIC_SCORES, toJsonb(a.getMetricScores()))
                        .set(TEST_CASE_METRIC_SCORES_AGGREGATED.CREATED_AT_MS, a.getCreatedAtMs())
                        .set(TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTED_AT_MS, a.getComputedAtMs())
                        .onConflict(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_SUITE_RUN_ID,
                                TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_CASE_ID,
                                TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTATION_ID)
                        .doUpdate()
                        .set(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.METRIC_SCORES,
                                excluded(TEST_CASE_METRIC_SCORES_AGGREGATED.METRIC_SCORES))
                        .set(
                                TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTED_AT_MS,
                                excluded(TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTED_AT_MS)))
                .toList();
        dsl.batch(queries).execute();
        log.debug("Batch upserted {} test case metric score aggregates", aggregates.size());
    }

    @Override
    public List<TestCaseMetricScoreAggregated> findByRunIdAndComputationId(UUID runId, UUID computationId) {
        return dsl.selectFrom(TEST_CASE_METRIC_SCORES_AGGREGATED)
                .where(TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_SUITE_RUN_ID.eq(runId.toString()))
                .and(TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTATION_ID.eq(computationId.toString()))
                .fetch(recordMapper::map);
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
