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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

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
    private final DurableWarningMerger durableWarningMerger;
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
     * For every test case in the dataset: branch on whether it carries a stored turn array
     * ({@link #processMultiTurnCase}) or not ({@link #processSingleTurnCase}), coercing and
     * re-validating accordingly. Template-variable cross-checks and FILE-reference validation are
     * deferred to Phase 2 since they require suite context.
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
                String rawTurns = tc.getMultiTurnData();
                CaseOutcome outcome = (rawTurns != null && !rawTurns.isBlank())
                        ? processMultiTurnCase(tc, datasetId, rawTurns, datasetSchema)
                        : processSingleTurnCase(tc, datasetId, datasetSchema);

                if (outcome.skipped()) {
                    skippedCount++;
                    continue;
                }
                coercedCellCount += outcome.coercedCellCount();
                if (outcome.valid()) {
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
     * Single-turn Phase 1 processing — unchanged behavior from before the multi-turn branch existed.
     * Coerces {@code data} against the dataset schema (skip on concurrent edit via {@link
     * TestCaseRepository#updateDataIfUnchanged}), re-validates it, carries forward any stored {@code
     * SOURCE_CONFLICT} warning via {@link DurableWarningMerger} (design D8 — a no-op here, since a
     * single-turn case never carries one today, but the rule applies to every recomputation pass
     * uniformly), and persists the verdict via {@link TestCaseRepository#updateValidationIfUnchanged}.
     */
    private CaseOutcome processSingleTurnCase(TestCase tc, UUID datasetId, List<FieldDefinitionDto> datasetSchema) {
        Map<String, Object> dataMap = warningsSerializer.deserializeMap(tc.getData());
        long seenAt = tc.getUpdatedAt() != null ? tc.getUpdatedAt() : 0L;

        CoercionResult coercion = schemaChangeCoercer.coerceMap(dataMap, datasetSchema);
        Map<String, Object> postCoercionData = coercion.coercedData();

        if (coercion.changed()) {
            long newUpdatedAt = clock.millis();
            int rowsAffected = testCaseRepository.updateDataIfUnchanged(
                    tc.getId(), datasetId, warningsSerializer.serializeMap(postCoercionData), seenAt, newUpdatedAt);
            if (rowsAffected == 0) {
                log.debug(
                        "Skipping revalidation for test case {} — concurrent edit detected (data update guard miss)",
                        tc.getId());
                return CaseOutcome.ofSkipped();
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
        ValidationResult merged = durableWarningMerger.merge(result, tc.getValidationWarnings());

        long validationUpdatedAt = clock.millis();
        int validationRows = testCaseRepository.updateValidationIfUnchanged(
                tc.getId(),
                datasetId,
                merged.isValid(),
                warningsSerializer.serializeWarnings(merged.getWarnings()),
                seenAt,
                validationUpdatedAt);
        if (validationRows == 0) {
            log.debug(
                    "Skipping validation update for test case {} — concurrent edit detected (validation update guard miss)",
                    tc.getId());
            return CaseOutcome.ofSkipped();
        }

        return CaseOutcome.ofCompleted(merged.isValid(), coercion.coercedCellCount());
    }

    /**
     * Multi-turn Phase 1 processing (design D7(b)): coerces the shared {@code data} map and every
     * turn map against the dataset schema, re-validates scope-aware via {@link
     * TestCaseValidationService#validateMultiTurn} against the <b>full</b> schema (it splits by
     * scope internally — passing a pre-split list would silently reclassify every per-turn field as
     * unknown), carries forward any stored {@code SOURCE_CONFLICT} warning via {@link
     * DurableWarningMerger} (design D8), and persists {@code data} and {@code multi_turn_data}
     * together via {@link TestCaseRepository#updateDataAndTurnsIfUnchanged} so a concurrent edit
     * skips both writes rather than applying one and losing the other.
     *
     * <p>The row's raw {@code multi_turn_data} is read with {@link
     * ValidationWarningsSerializer#deserializeTurnsStrict}, which throws on unreadable JSON instead
     * of collapsing it to {@code null} the way the lenient {@code deserializeTurns} does (design D6):
     * a row whose turns cannot be read is skipped entirely — neither guarded write runs — because
     * writing {@code null} back would convert the case to single-turn and destroy every turn.
     */
    private CaseOutcome processMultiTurnCase(
            TestCase tc, UUID datasetId, String rawTurns, List<FieldDefinitionDto> datasetSchema) {
        List<Map<String, Object>> turns;
        try {
            turns = warningsSerializer.deserializeTurnsStrict(rawTurns);
        } catch (JacksonException e) {
            log.warn(
                    "Skipping test case {} during dataset revalidation: stored multi_turn_data is unreadable, "
                            + "leaving it untouched to avoid destroying its turns: {}",
                    tc.getId(),
                    e.getMessage(),
                    e);
            return CaseOutcome.ofSkipped();
        }

        if (turns == null) {
            // The raw column is non-blank but parses to the JSON literal `null` (deserializeTurnsStrict
            // returns null for this input, same as for an absent column). Fall back to the single-turn
            // path, which leaves tc.getMultiTurnData() untouched (only `data` is written) rather than
            // re-serializing an empty turn array and silently changing its shape.
            return processSingleTurnCase(tc, datasetId, datasetSchema);
        }

        Map<String, Object> sharedData = warningsSerializer.deserializeMap(tc.getData());
        long seenAt = tc.getUpdatedAt() != null ? tc.getUpdatedAt() : 0L;

        CoercionResult sharedCoercion = schemaChangeCoercer.coerceMap(sharedData, datasetSchema);
        Map<String, Object> postCoercionShared = sharedCoercion.coercedData();

        List<Map<String, Object>> postCoercionTurns = new ArrayList<>(turns.size());
        boolean anyChanged = sharedCoercion.changed();
        long coercedCellCount = sharedCoercion.coercedCellCount();
        for (Map<String, Object> turn : turns) {
            CoercionResult turnCoercion = schemaChangeCoercer.coerceMap(turn, datasetSchema);
            postCoercionTurns.add(turnCoercion.coercedData());
            if (turnCoercion.changed()) {
                anyChanged = true;
            }
            coercedCellCount += turnCoercion.coercedCellCount();
        }

        // Widened relative to the single-turn guard: a turn-only change must trigger the write too,
        // while an unchanged multi-turn case still writes nothing (otherwise every revalidation would
        // bump updated_at_ms on every multi-turn case).
        if (anyChanged) {
            long newUpdatedAt = clock.millis();
            int rowsAffected = testCaseRepository.updateDataAndTurnsIfUnchanged(
                    tc.getId(),
                    datasetId,
                    warningsSerializer.serializeMap(postCoercionShared),
                    warningsSerializer.serializeTurns(postCoercionTurns),
                    seenAt,
                    newUpdatedAt);
            if (rowsAffected == 0) {
                log.debug(
                        "Skipping revalidation for test case {} — concurrent edit detected "
                                + "(data/turns update guard miss)",
                        tc.getId());
                return CaseOutcome.ofSkipped();
            }
            seenAt = newUpdatedAt;
        }

        ValidationResult result = testCaseValidationService.validateMultiTurn(
                postCoercionShared,
                postCoercionTurns,
                datasetSchema,
                /* effectiveTemplate */ null,
                /* effectiveBindings */ List.of(),
                /* hasOverrides     */ false,
                /* datasetId        */ datasetId);
        ValidationResult merged = durableWarningMerger.merge(result, tc.getValidationWarnings());

        long validationUpdatedAt = clock.millis();
        int validationRows = testCaseRepository.updateValidationIfUnchanged(
                tc.getId(),
                datasetId,
                merged.isValid(),
                warningsSerializer.serializeWarnings(merged.getWarnings()),
                seenAt,
                validationUpdatedAt);
        if (validationRows == 0) {
            log.debug(
                    "Skipping validation update for test case {} — concurrent edit detected (validation update guard miss)",
                    tc.getId());
            return CaseOutcome.ofSkipped();
        }

        return CaseOutcome.ofCompleted(merged.isValid(), coercedCellCount);
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

    /**
     * Pure-data carrier for one test case's Phase 1 outcome, returned by {@link #processSingleTurnCase}
     * and {@link #processMultiTurnCase} so {@link #runPhase1}'s batch loop can aggregate counters without
     * either helper reaching back into the loop's local variables.
     */
    private record CaseOutcome(boolean skipped, boolean valid, long coercedCellCount) {
        static CaseOutcome ofSkipped() {
            return new CaseOutcome(true, false, 0L);
        }

        static CaseOutcome ofCompleted(boolean valid, long coercedCellCount) {
            return new CaseOutcome(false, valid, coercedCellCount);
        }
    }
}
