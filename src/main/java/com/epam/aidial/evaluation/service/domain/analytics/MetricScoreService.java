package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the run job's Phase-3 metric-score computation output. Results are read through the unified
 * Query DSL entity {@code metric_score_results} (no dedicated read API). The per-metric statistics are
 * code-defined; the Phase-3 computation produces results and hands them here to persist.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class MetricScoreService {

    private final MetricScoreResultRepository resultRepository;

    @Transactional("analyticsTransactionManager")
    public void saveAll(List<MetricScoreResult> results) {
        resultRepository.saveAll(results);
    }
}
