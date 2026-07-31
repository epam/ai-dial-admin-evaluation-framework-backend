package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.RunMetricSnapshotMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class RunMetricSnapshotService {

    private final RunMetricSnapshotRepository snapshotRepository;
    private final TestSuiteRunRepository runRepository;
    private final RunMetricSnapshotMapper snapshotMapper;
    private final FilterParser filterParser;

    @Transactional("analyticsTransactionManager")
    public BatchWriteResponseDto batchCreate(RunMetricSnapshotBatchWriteRequestDto request) {
        runRepository
                .findById(request.getTestSuiteRunId())
                .orElseThrow(
                        () -> new EntityNotFoundException("Test suite run not found: " + request.getTestSuiteRunId()));

        List<RunMetricSnapshot> entities = request.getSnapshots().stream()
                .map(item -> snapshotMapper.toEntity(
                        item, request.getComputationId(), request.getTestSuiteRunId(), request.getComputedAtMs()))
                .toList();

        snapshotRepository.saveAll(entities);
        log.info("Batch created {} run metric snapshots for run {}", entities.size(), request.getTestSuiteRunId());

        return BatchWriteResponseDto.builder()
                .totalItems(request.getSnapshots().size())
                .build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public List<RunMetricSnapshotResponseDto> listByFilter(List<String> filterParams) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        UUID runId = filters.stream()
                .filter(f -> "runId".equals(f.getField()))
                .findFirst()
                .map(f -> UUID.fromString(f.getRawValue()))
                .orElseThrow(() -> new ValidationException("Filter 'runId' is required"));
        return listByRunId(runId);
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public List<RunMetricSnapshotResponseDto> listByRunId(UUID runId) {
        return snapshotRepository.findByRunId(runId).stream()
                .map(snapshotMapper::toDto)
                .toList();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public Optional<UUID> findLatestComputationId(UUID runId) {
        return snapshotRepository.findLatestComputationId(runId);
    }
}
