package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
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
 * Exposes computed metric-score results for a run. Definitions are seed-only ({@code DEFAULT}, applied
 * to every run) and have no management API — the run job's Phase-3 computation reads them directly and
 * writes results, which this service reads back.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class MetricScoreService {

    private final MetricScoreResultRepository resultRepository;
    private final MetricScoreResultMapper resultMapper;
    private final ComputationResolver computationResolver;

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
