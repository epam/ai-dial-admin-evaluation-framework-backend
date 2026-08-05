package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RevalidationTask;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.RevalidationTaskRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RevalidationStatus;
import com.epam.aidial.evaluation.runner.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer;
import com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer.CoercionResult;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@LogExecution
@Slf4j
@RequiredArgsConstructor
public class RevalidationService {

    private final RevalidationTaskRepository revalidationTaskRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestSuiteRepository testSuiteRepository;
    private final DatasetRepository datasetRepository;
    private final TestCaseValidationService testCaseValidationService;
    private final SuiteValidationService suiteValidationService;
    private final TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private final JsonbMapper jsonbMapper;
    private final RevalidationProperties revalidationProperties;
    private final ValidationWarningsSerializer warningsSerializer;
    private final SchemaChangeCoercer schemaChangeCoercer;
    private final Clock clock;

    /**
     * Starts async re-validation rooted at the given dataset. Returns immediately with task descriptor.
     * Phase 1 reprocesses every test case in the dataset against the dataset's testCaseSchema;
     * Phase 2 fans out to every suite that references the dataset and refreshes its (isValid,
     * validationWarnings, TSMD validation) tuple.
     */
    public RevalidationTaskDto startDatasetRevalidation(UUID datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
        long total = testCaseRepository.countByDatasetId(datasetId);

        RevalidationTask task = RevalidationTask.builder()
                .datasetId(datasetId)
                .status(RevalidationStatus.PENDING.name())
                .totalCases((int) total)
                .processedCases(0)
                .validCount(0)
                .invalidCount(0)
                .build();
        revalidationTaskRepository.save(task);

        runDatasetRevalidationAsync(task.getId(), datasetId);
        return toDto(task);
    }

    @Async
    public void runDatasetRevalidationAsync(UUID taskId, UUID datasetId) {
        try {
            RevalidationTask task = revalidationTaskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return;
            }
            task.setStatus(RevalidationStatus.RUNNING.name());
            revalidationTaskRepository.update(task);

            Optional<Dataset> datasetOpt = datasetRepository.findById(datasetId);
            if (datasetOpt.isEmpty()) {
                task.setStatus(RevalidationStatus.FAILED.name());
                task.setErrorMessage("Dataset not found");
                task.setCompletedAtMs(clock.millis());
                revalidationTaskRepository.update(task);
                return;
            }
            Dataset dataset = datasetOpt.get();
            List<FieldDefinitionDto> datasetSchema = jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema());

            Phase1Outcome phase1 = runPhase1(task, datasetId, datasetSchema);

            // Phase 2 runs even when Phase 1 times out; the suite-level revalidation result is independent
            // of per-test-case progress and still useful operationally to surface suite-level breakage.
            runPhase2(datasetId, datasetSchema, dataset.getTestCaseSchema());

            if (RevalidationStatus.RUNNING.name().equals(task.getStatus())) {
                task.setStatus(RevalidationStatus.COMPLETED.name());
                task.setCompletedAtMs(clock.millis());
                revalidationTaskRepository.update(task);
            }
            log.info(
                    "Revalidated dataset={} task={}: total={} valid={} invalid={} coerced_cells={} skipped={}",
                    datasetId,
                    taskId,
                    phase1.processedCases,
                    phase1.validCount,
                    phase1.invalidCount,
                    phase1.coercedCellCount,
                    phase1.skippedCount);
        } catch (Exception e) {
            log.warn("Revalidation failed for task {}: {}", taskId, e.getMessage(), e);
            revalidationTaskRepository.findById(taskId).ifPresent(t -> {
                t.setStatus(RevalidationStatus.FAILED.name());
                t.setErrorMessage(
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                t.setCompletedAtMs(clock.millis());
                revalidationTaskRepository.update(t);
            });
        }
    }

    /**
     * Phase 1 — dataset-rooted test-case coercion + schema-shape validation.
     * For every test case in the dataset: coerce the {@code data} map against the new schema
     * (skip the row on concurrent edit via {@link TestCaseRepository#updateDataIfUnchanged}),
     * then validate {@code data} against the dataset schema (template-variable / file-ref checks
     * are deferred to Phase 2 since they require suite context). Writes (isValid, warnings)
     * via {@link TestCaseRepository#updateValidationIfUnchanged}.
     */
    private Phase1Outcome runPhase1(RevalidationTask task, UUID datasetId, List<FieldDefinitionDto> datasetSchema) {
        int batchSize = revalidationProperties.getBatchSize();
        int timeoutMinutes = revalidationProperties.getTimeoutMinutes();
        long timeoutMs = timeoutMinutes * 60L * 1000;
        long startMs = clock.millis();

        int offset = 0;
        int processedCases = 0;
        int validCount = 0;
        int invalidCount = 0;
        long coercedCellCount = 0L;
        int skippedCount = 0;
        long total = task.getTotalCases();

        while (offset < total) {
            if (clock.millis() - startMs > timeoutMs) {
                task.setStatus(RevalidationStatus.TIMED_OUT.name());
                task.setErrorMessage("Revalidation timed out after " + timeoutMinutes + " minutes");
                break;
            }

            List<TestCase> batch = testCaseRepository.findBatchByDatasetId(datasetId, offset, batchSize);
            for (TestCase tc : batch) {
                processedCases++;
                Map<String, Object> dataMap = warningsSerializer.deserializeMap(tc.getData());
                long seenAt = tc.getUpdatedAt() != null ? tc.getUpdatedAt() : 0L;

                CoercionResult coercion = schemaChangeCoercer.coerceMap(dataMap, datasetSchema);
                Map<String, Object> postCoercionData = coercion.coercedData();

                if (coercion.changed()) {
                    long newUpdatedAt = clock.millis();
                    int rowsAffected = testCaseRepository.updateDataIfUnchanged(
                            tc.getId(),
                            datasetId,
                            warningsSerializer.serializeMap(postCoercionData),
                            seenAt,
                            newUpdatedAt);
                    if (rowsAffected == 0) {
                        log.debug(
                                "Skipping revalidation for test case {} — concurrent edit detected (data update guard miss)",
                                tc.getId());
                        skippedCount++;
                        continue;
                    }
                    seenAt = newUpdatedAt;
                }

                // Dataset-rooted validation: data-vs-schema shape only.
                // Template-variable cross-checks and FILE-reference validation move to Phase 2
                // (per-suite) where the necessary template/bindings + file-bucket context exist.
                ValidationResult result = testCaseValidationService.validateTestCase(
                        postCoercionData,
                        datasetSchema,
                        /* effectiveTemplate */ null,
                        /* effectiveBindings */ List.of(),
                        /* hasOverrides     */ false,
                        /* datasetId        */ datasetId);

                long validationUpdatedAt = clock.millis();
                int validationRows = testCaseRepository.updateValidationIfUnchanged(
                        tc.getId(),
                        datasetId,
                        result.isValid(),
                        warningsSerializer.serializeWarnings(result.getWarnings()),
                        seenAt,
                        validationUpdatedAt);
                if (validationRows == 0) {
                    log.debug(
                            "Skipping validation update for test case {} — concurrent edit detected (validation update guard miss)",
                            tc.getId());
                    skippedCount++;
                    continue;
                }

                coercedCellCount += coercion.coercedCellCount();
                if (result.isValid()) {
                    validCount++;
                } else {
                    invalidCount++;
                }
            }

            offset += batch.size();
            task.setProcessedCases(processedCases);
            task.setValidCount(validCount);
            task.setInvalidCount(invalidCount);
            task.setCoercedCellCount(coercedCellCount);
            revalidationTaskRepository.update(task);

            if (batch.isEmpty()) {
                // Defensive: avoid infinite loop if the repository returns 0 rows before reaching total
                // (e.g. concurrent deletes shrunk the dataset mid-run).
                break;
            }
        }

        return new Phase1Outcome(processedCases, validCount, invalidCount, coercedCellCount, skippedCount);
    }

    /**
     * Phase 2 — per-suite resilient fan-out. For every suite referencing the dataset:
     * revalidate the suite's wiring against the (new) dataset schema, revalidate every TSMD
     * attached to the suite, and write the (isValid, validationWarnings, updatedAt) tuple.
     */
    private void runPhase2(UUID datasetId, List<FieldDefinitionDto> datasetSchema, String datasetSchemaJson) {
        for (TestSuite suite : testSuiteRepository.findSuitesReferencingDataset(datasetId)) {
            // Deliberately broad: per-suite isolation boundary in batch revalidation (design.md D4).
            // Catching narrow exception types here risks the entire suite list aborting on an
            // unexpected runtime error in one suite.
            try {
                ValidationResult validationResult = suiteValidationService.validateSuite(suite, datasetSchema);
                testSuiteMetricDefinitionService.revalidateAllForSuite(
                        suite.getId(), datasetSchemaJson, suite.getResponseColumns());
                testSuiteRepository.updateValidation(
                        suite.getId(),
                        validationResult.isValid(),
                        warningsSerializer.serializeWarnings(validationResult.getWarnings()),
                        clock.millis());
            } catch (Exception e) {
                log.warn("Suite revalidation failed for suite={}: {}", suite.getId(), e.getMessage(), e);
            }
        }
    }

    public Optional<RevalidationTaskDto> getTask(UUID datasetId, UUID taskId) {
        return revalidationTaskRepository
                .findByIdAndDatasetId(taskId, datasetId)
                .map(this::toDto);
    }

    /**
     * Lists revalidation tasks for a dataset with pagination. For use from web layer (avoids exposing PageRequest).
     */
    public Page<RevalidationTaskDto> listTasks(UUID datasetId, int page, int size) {
        PageRequest pageRequest = PageRequest.builder().page(page).size(size).build();
        return listTasks(datasetId, pageRequest);
    }

    public Page<RevalidationTaskDto> listTasks(UUID datasetId, PageRequest pageRequest) {
        Page<RevalidationTask> page = revalidationTaskRepository.findAllByDatasetId(datasetId, pageRequest);
        return Page.of(page.getContent().stream().map(this::toDto).toList(), pageRequest, page.getTotalElements());
    }

    private RevalidationTaskDto toDto(RevalidationTask task) {
        RevalidationStatus status;
        try {
            status = RevalidationStatus.valueOf(task.getStatus());
        } catch (IllegalArgumentException e) {
            status = RevalidationStatus.PENDING;
        }
        return RevalidationTaskDto.builder()
                .taskId(task.getId())
                .datasetId(task.getDatasetId())
                .status(status)
                .totalCases(task.getTotalCases())
                .processedCases(task.getProcessedCases())
                .validCount(task.getValidCount())
                .invalidCount(task.getInvalidCount())
                .startedAt(task.getStartedAtMs())
                .completedAt(task.getCompletedAtMs())
                .errorMessage(task.getErrorMessage())
                .coercedCellCount(task.getCoercedCellCount() != null ? task.getCoercedCellCount() : 0L)
                .build();
    }

    /**
     * Pure-data carrier for the Phase 1 result, returned to {@link #runDatasetRevalidationAsync}
     * so the summary log line and downstream Phase 2 can read the totals without poking at
     * the {@link RevalidationTask} entity (which is being concurrently written).
     */
    private record Phase1Outcome(
            int processedCases, int validCount, int invalidCount, long coercedCellCount, int skippedCount) {}
}
