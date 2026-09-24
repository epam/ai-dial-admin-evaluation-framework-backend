package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseMetricScoreAggregated;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseMetricScoreAggregatedRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseMetricScoreAggregatedBatchWriteItemDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only write path for {@code test_case_metric_scores_aggregated}, populated by the in-process
 * metric evaluation engine right after each {@code test_case_eval_summaries} flush's per-row score write
 * (see {@code InProcessMetricEvaluationExecutor}). A test case touched by more than one flush batch is
 * fully re-aggregated (and upserted) on every touch, so {@code computedAtMs} advances on every call while
 * {@code createdAtMs} is only honored by the database on a row's first insert.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestCaseMetricScoreAggregatedService {

    private final TestCaseMetricScoreAggregatedRepository testCaseMetricScoreAggregatedRepository;

    @Transactional("analyticsTransactionManager")
    public void batchUpsert(long computedAtMs, List<TestCaseMetricScoreAggregatedBatchWriteItemDto> items) {
        if (items.isEmpty()) {
            return;
        }
        List<TestCaseMetricScoreAggregated> entities = items.stream()
                .map(item -> TestCaseMetricScoreAggregated.builder()
                        .id(UUID.randomUUID())
                        .testSuiteRunId(item.getTestSuiteRunId())
                        .testCaseId(item.getTestCaseId())
                        .computationId(item.getComputationId())
                        .metricScores(item.getMetricScores())
                        .createdAtMs(computedAtMs)
                        .computedAtMs(computedAtMs)
                        .build())
                .toList();
        testCaseMetricScoreAggregatedRepository.saveAll(entities);
        log.debug("Batch upserted {} test case metric score aggregates", entities.size());
    }
}
