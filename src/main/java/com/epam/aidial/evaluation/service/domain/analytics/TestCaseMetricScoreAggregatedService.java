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
 * Internal-only, insert-only write path for {@code test_case_metric_scores_aggregated}, populated by the
 * in-process metric evaluation engine once per computation, after the last {@code test_case_eval_summaries}
 * flush (see {@code InProcessMetricEvaluationExecutor}). Each test case is aggregated exactly once over its
 * complete row set, so an already-existing row for the same key is left untouched.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestCaseMetricScoreAggregatedService {

    private final TestCaseMetricScoreAggregatedRepository testCaseMetricScoreAggregatedRepository;

    @Transactional("analyticsTransactionManager")
    public void batchInsert(long computedAtMs, List<TestCaseMetricScoreAggregatedBatchWriteItemDto> items) {
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
        log.debug("Batch inserted {} test case metric score aggregates", entities.size());
    }
}
