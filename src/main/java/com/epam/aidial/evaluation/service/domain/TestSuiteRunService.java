package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.configuration.security.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.analytics.EvalResultsCsvParser;
import com.epam.aidial.evaluation.service.domain.analytics.EvalResultsImportService;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorCategory;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.TooManyRunsException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator;
import com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteRunMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunService {

    private final TestSuiteRunRepository testSuiteRunRepository;
    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseService testCaseService;
    private final RunnableTestCaseCounter runnableTestCaseCounter;
    private final TestSuiteRunProperties properties;
    private final TestSuiteEvaluationJob evaluationJob;
    private final ExecutionSettingsValidator executionSettingsValidator;
    private final TestSuiteRunSseService sseService;
    private final TestSuiteRunMapper mapper;
    private final FilterParser filterParser;
    private final SortParser sortParser;
    private final ObjectMapper objectMapper;
    private final EvalResultsImportService evalResultsImportService;
    private final EvalResultsCsvParser evalResultsCsvParser;

    @Transactional("metaTransactionManager")
    public TestSuiteRunResponseDto createRun(UUID testSuiteId, RunConfigDto config) {
        TestSuite testSuite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + testSuiteId));

        // Unbound-suite guard fires BEFORE the valid=false check so the dataset-binding failure
        // mode is reported even when the suite would also fail validation.
        if (testSuite.getDatasetId() == null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.SUITE_HAS_NO_DATASET,
                    "Cannot start a run: test suite " + testSuiteId
                            + " is not bound to a dataset (datasetId is null).");
        }

        if (!testSuite.isValid()) {
            throw new InvalidOperationException("Cannot create a run for test suite with id: " + testSuiteId
                    + ". The test suite is not in a valid state.");
        }

        // Multi-turn test cases are supported only for HTTP chat-completions suites. Reject an MCP suite
        // whose dataset contains any multi-turn case (409 INVALID_OPERATION).
        if (testSuite.getSuiteType() == SuiteType.MCP_TOOL
                && testCaseService.datasetHasMultiTurnCases(testSuite.getDatasetId())) {
            throw new InvalidOperationException("Cannot create a run: MCP suites do not support multi-turn test cases");
        }

        List<UUID> disabledIds = deserializeDisabledIds(testSuite.getDisabledTestCaseIds());
        long numberOfTestCases = runnableTestCaseCounter.countRunnable(
                testSuite.getDatasetId(), testSuite.getTestCaseFilter(), disabledIds);
        if (numberOfTestCases == 0) {
            throw new InvalidOperationException("Suite has no valid and enabled test cases");
        }

        int maxRuns = properties.getRunConfig().getMaxNumberOfRuns();
        if (config.getNumberOfRuns() > maxRuns) {
            throw new ValidationException("numberOfRuns must not exceed " + maxRuns);
        }

        executionSettingsValidator.validate(config.getExecution(), config.getRetry());

        enforceConcurrencyLimits(testSuiteId);

        String runConfigJson;
        try {
            runConfigJson = objectMapper.writeValueAsString(config);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize runConfig", ex);
        }

        TestSuiteRun run =
                createAndSaveRun(testSuiteId, config.getTestRunName(), (int) numberOfTestCases, runConfigJson);

        UUID runId = run.getId();
        String token = AuthorizationTokenHolder.getToken();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatchEvaluation(runId, token, false, () -> {
                    String errorDetails = evaluationJob.buildErrorDetails(
                            "EXECUTOR_REJECTED",
                            RunErrorCategory.RESOURCE_LIMIT,
                            "The execution queue is full. Please try again later.",
                            null);
                    testSuiteRunRepository.updateToFailed(
                            runId,
                            "Executor rejected job submission",
                            errorDetails,
                            System.currentTimeMillis(),
                            System.currentTimeMillis());
                });
            }
        });

        return mapper.toDto(run);
    }

    /**
     * Imports a batch of already-produced eval results for an existing, dataset-bound suite: parses the
     * uploaded CSV via {@link EvalResultsCsvParser}, creates a {@code PENDING} run, then — once
     * this transaction commits — persists the results (analytics datasource) and triggers Phase 2 (metric
     * evaluation) + Phase 3 (score computation) asynchronously via
     * {@link TestSuiteEvaluationJob#executeRunAsync} with Phase 1 skipped. Dispatch is deferred to
     * {@code afterCommit} for the same reason {@link #createRun} defers it: the async job reads the run by
     * id on its own connection and must not race a not-yet-visible row.
     */
    @Transactional("metaTransactionManager")
    public TestSuiteRunResponseDto importResultsAndEvaluate(
            UUID testSuiteId, String testRunName, InputStream csv, long contentLength, char delimiter) {
        TestSuite testSuite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found with id: " + testSuiteId));

        if (testSuite.getDatasetId() == null) {
            throw new DatasetVisibilityRuleException(
                    DatasetVisibilityErrorCode.SUITE_HAS_NO_DATASET,
                    "Cannot import eval results: test suite " + testSuiteId
                            + " is not bound to a dataset (datasetId is null).");
        }

        if (!testSuite.isValid()) {
            throw new InvalidOperationException("Cannot import eval results for test suite with id: " + testSuiteId
                    + ". The test suite is not in a valid state.");
        }

        List<TestCaseRunResult> results =
                evalResultsCsvParser.parse(testSuite.getDatasetId(), csv, contentLength, delimiter);
        evalResultsImportService.validateBatch(results);

        enforceConcurrencyLimits(testSuiteId);

        TestSuiteRun run = createAndSaveRun(testSuiteId, testRunName, results.size(), "{}");

        UUID runId = run.getId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    evalResultsImportService.persistResults(testSuiteId, run, results);
                } catch (Exception ex) {
                    log.error("Failed to persist imported eval results for run {}: {}", runId, ex.getMessage(), ex);
                    markRunFailed(runId, "Failed to persist imported eval results", "IMPORT_RESULTS_PERSIST_FAILED");
                    return;
                }

                dispatchEvaluation(
                        runId,
                        null,
                        true,
                        () -> markRunFailed(runId, "Executor rejected job submission", "EXECUTOR_REJECTED"));
            }
        });

        return mapper.toDto(run);
    }

    /**
     * Applies the global and per-suite concurrent-run limits shared, byte-identical, by
     * {@link #createRun} and {@link #importResultsAndEvaluate}.
     */
    private void enforceConcurrencyLimits(UUID testSuiteId) {
        List<String> activeStatuses = List.of(RunStatus.PENDING.name(), RunStatus.RUNNING.name());

        int globalActive = testSuiteRunRepository.countByStatuses(activeStatuses);
        if (globalActive >= properties.getLimits().getMaxConcurrentRunsGlobal()) {
            throw new TooManyRunsException(
                    "Global concurrent run limit reached",
                    Map.of(
                            "activeRunsGlobal",
                            globalActive,
                            "maxRunsGlobal",
                            properties.getLimits().getMaxConcurrentRunsGlobal()));
        }

        int suiteActive = testSuiteRunRepository.countByTestSuiteIdAndStatuses(testSuiteId, activeStatuses);
        if (suiteActive >= properties.getLimits().getMaxConcurrentRunsPerSuite()) {
            throw new TooManyRunsException(
                    "Suite concurrent run limit reached",
                    Map.of(
                            "activeRunsForSuite",
                            suiteActive,
                            "maxRunsPerSuite",
                            properties.getLimits().getMaxConcurrentRunsPerSuite()));
        }
    }

    /**
     * Saves a new {@code PENDING} {@link TestSuiteRun}, resolving {@code requestedTestRunName} to a
     * generated {@code "Run #<seq>"} name when blank, and mapping a run-name unique-constraint
     * violation to {@link com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException}.
     * Shared, byte-identical modulo {@code runConfigJson}, by {@link #createRun} (its serialized
     * {@link RunConfigDto}) and {@link #importResultsAndEvaluate} (a fixed {@code "{}"}).
     */
    private TestSuiteRun createAndSaveRun(
            UUID testSuiteId, String requestedTestRunName, int numberOfTestCases, String runConfigJson) {
        String testRunName = requestedTestRunName;
        if (testRunName == null || testRunName.isBlank()) {
            long seqVal = testSuiteRunRepository.nextRunNameSequenceValue();
            testRunName = "Run #" + seqVal;
        }

        TestSuiteRun run = TestSuiteRun.builder()
                .testSuiteId(testSuiteId)
                .testRunName(testRunName)
                .status(RunStatus.PENDING.name())
                .runConfig(runConfigJson)
                .numberOfTestCases(numberOfTestCases)
                .build();

        try {
            testSuiteRunRepository.save(run);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex,
                    "A test suite run with name '" + testRunName + "' already exists for this test suite",
                    testRunName);
            throw ex;
        }

        return run;
    }

    /**
     * Marks an import run {@code FAILED} and notifies SSE listeners. Uses {@code System.currentTimeMillis()}
     * directly rather than an injected {@code Clock}, consistent with {@link #createRun}'s own pre-existing
     * timestamp handling in this failure path (see {@code design.md} Decision 6). Used only by
     * {@link #importResultsAndEvaluate}'s {@code afterCommit} callback.
     */
    private void markRunFailed(UUID runId, String errorMessage, String errorCode) {
        long now = System.currentTimeMillis();
        String errorDetails = evaluationJob.buildErrorDetails(errorCode, RunErrorCategory.INTERNAL, errorMessage, null);
        testSuiteRunRepository.updateToFailed(runId, errorMessage, errorDetails, now, now);
        testSuiteRunRepository.findById(runId).ifPresent(sseService::notifyStatusUpdate);
    }

    /**
     * Registers the run's cancellation signal and dispatches Phase 1–3 (or Phase 2/3 only, when
     * {@code skipDeploymentPhase} is {@code true}) via {@link TestSuiteEvaluationJob#executeRunAsync}.
     * If the executor rejects the submission, removes the cancellation signal and invokes
     * {@code onRejected} so each caller can apply its own failure-compensation logic. Any other
     * exception also removes the cancellation signal before being rethrown.
     */
    private void dispatchEvaluation(UUID runId, String token, boolean skipDeploymentPhase, Runnable onRejected) {
        evaluationJob.registerCancellationSignal(runId);
        try {
            evaluationJob.executeRunAsync(runId, token, skipDeploymentPhase);
        } catch (RejectedExecutionException ex) {
            evaluationJob.removeCancellationSignal(runId);
            log.warn("Executor rejected job submission for run {}: {}", runId, ex.getMessage(), ex);
            onRejected.run();
        } catch (Exception ex) {
            evaluationJob.removeCancellationSignal(runId);
            throw ex;
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TestSuiteRunResponseDto getRun(UUID runId) {
        TestSuiteRun run = testSuiteRunRepository
                .findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuiteRun not found with id: " + runId));
        return mapper.toDto(run);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public TestSuiteRunResponseDto getLatestRun(UUID suiteId) {
        TestSuiteRun run = testSuiteRunRepository
                .findLatestByTestSuiteId(suiteId)
                .orElseThrow(() -> new EntityNotFoundException("No runs found for test suite with id: " + suiteId));
        return mapper.toDto(run);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<TestSuiteRunResponseDto> listRuns(
            int page, int size, List<String> sort, List<String> filter, Boolean includeTotalCount) {
        PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        boolean includeTotal = Boolean.TRUE.equals(includeTotalCount);

        Page<TestSuiteRun> runPage = testSuiteRunRepository.findAll(pageRequest, filters, includeTotal);
        return PageResponseDto.from(runPage, mapper::toDto, includeTotal);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRunResponseDto cancelRun(UUID runId) {
        TestSuiteRun run = testSuiteRunRepository
                .findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuiteRun not found with id: " + runId));

        if (RunStatus.isTerminal(run.getStatus())) {
            throw new InvalidOperationException("Cannot cancel run with status: " + run.getStatus());
        }

        if (run.getStatus().equals(RunStatus.PENDING.name())) {
            int updated = testSuiteRunRepository.updateStatusOptimistic(
                    runId, RunStatus.CANCELLED.name(), RunStatus.PENDING.name());
            if (updated > 0) {
                TestSuiteRun cancelled = testSuiteRunRepository.findById(runId).orElseThrow();
                sseService.notifyStatusUpdate(cancelled);
                return mapper.toDto(cancelled);
            }
            run = testSuiteRunRepository.findById(runId).orElseThrow();
        }

        evaluationJob.interruptRun(runId);
        return mapper.toDto(run);
    }

    @Transactional("metaTransactionManager")
    public void deleteRun(UUID runId) {
        TestSuiteRun run = testSuiteRunRepository
                .findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuiteRun not found with id: " + runId));

        if (!RunStatus.isTerminal(run.getStatus())) {
            throw new InvalidOperationException(
                    "Cannot delete a test suite run with status " + run.getStatus() + ". Cancel it first.");
        }

        testSuiteRunRepository.deleteById(runId);
    }

    @Transactional("metaTransactionManager")
    public TestSuiteRunResponseDto updateRunName(UUID runId, String newName) {
        TestSuiteRun run = testSuiteRunRepository
                .findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuiteRun not found with id: " + runId));

        try {
            testSuiteRunRepository.updateTestRunName(runId, newName);
        } catch (DataIntegrityViolationException ex) {
            UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                    ex, "A test suite run with name '" + newName + "' already exists for this test suite", newName);
            throw ex;
        }

        return mapper.toDto(testSuiteRunRepository.findById(runId).orElseThrow());
    }

    /**
     * Deserialises a suite's {@code disabledTestCaseIds} JSONB column into a typed list.
     * Returns an empty list on null/blank or malformed payloads so run creation does not
     * brick on a single corrupt row.
     */
    private List<UUID> deserializeDisabledIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<>() {});
            List<UUID> ids = new java.util.ArrayList<>(raw.size());
            for (String s : raw) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(s));
                } catch (IllegalArgumentException ex) {
                    log.warn("Skipping malformed UUID in disabledTestCaseIds: {}", s, ex);
                }
            }
            return ids;
        } catch (JacksonException ex) {
            log.warn("Failed to deserialize disabledTestCaseIds JSON: {}", ex.getMessage(), ex);
            return List.of();
        }
    }
}
