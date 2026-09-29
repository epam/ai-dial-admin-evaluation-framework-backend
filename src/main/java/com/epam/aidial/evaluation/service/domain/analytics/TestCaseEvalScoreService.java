package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseEvalScoreBatchWriteItemDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only write path for {@code test_case_eval_scores}, populated by the in-process metric
 * evaluation engine once per test case after the last {@code test_case_eval_summaries} flush (see
 * {@code InProcessMetricEvaluationExecutor}). No external REST endpoint exists for this table —
 * scores are read back via the LEFT JOIN into the existing eval-summary read surface, or directly
 * (deduplicated) via the {@code test_case_eval_scores} Query DSL entity.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestCaseEvalScoreService {

    private final TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @Transactional("analyticsTransactionManager")
    public void batchInsert(long computedAtMs, List<TestCaseEvalScoreBatchWriteItemDto> items) {
        if (items.isEmpty()) {
            return;
        }
        List<TestCaseEvalScore> entities = items.stream()
                .map(item -> TestCaseEvalScore.builder()
                        .id(UUID.randomUUID())
                        .testSuiteRunId(item.getTestSuiteRunId())
                        .testCaseId(item.getTestCaseId())
                        .testCaseName(item.getTestCaseName())
                        .computationId(item.getComputationId())
                        .executionStatus(item.getExecutionStatus())
                        .score(item.getScore())
                        .passed(item.getPassed())
                        .computedAtMs(computedAtMs)
                        .build())
                .toList();
        testCaseEvalScoreRepository.saveAll(entities);
        log.debug("Batch inserted {} eval summary scores", entities.size());
    }
}
