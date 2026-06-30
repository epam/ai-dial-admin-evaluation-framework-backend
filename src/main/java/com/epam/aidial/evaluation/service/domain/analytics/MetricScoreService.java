package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreResultResponseDto;
import com.epam.aidial.evaluation.service.domain.mapper.MetricScoreResultMapper;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns metric-score results: persists the run job's Phase-3 computation output and exposes it for
 * reading. Definitions are seed-only ({@code DEFAULT}, applied to every run) and have no management
 * API — the Phase-3 computation reads them directly, computes results, and hands them here to persist.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class MetricScoreService {

    private final MetricScoreResultRepository resultRepository;
    private final MetricScoreResultMapper resultMapper;
    private final ComputationResolver computationResolver;

    @Transactional("analyticsTransactionManager")
    public void saveAll(List<MetricScoreResult> results) {
        resultRepository.saveAll(results);
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public List<MetricScoreResultResponseDto> listResults(UUID testSuiteRunId, String computation) {
        return computationResolver
                .resolve(computation, testSuiteRunId)
                .map(computationId -> resultRepository.findByRunAndComputation(testSuiteRunId, computationId).stream()
                        .map(resultMapper::toDto)
                        .toList())
                .orElseGet(List::of);
    }
}
