package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RevalidationTask;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.RevalidationTaskRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer;
import com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer.CoercionResult;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationStatus;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@DisplayName("RevalidationService — dataset-rooted two-phase flow")
@ExtendWith(MockitoExtension.class)
class RevalidationServiceTest {

    @Mock
    private RevalidationTaskRepository revalidationTaskRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private TestCaseValidationService testCaseValidationService;

    @Mock
    private SuiteValidationService suiteValidationService;

    @Mock
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;

    @Mock
    private RevalidationProperties revalidationProperties;

    @Mock
    private SchemaChangeCoercer schemaChangeCoercer;

    private RevalidationService service;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC);
    private final UUID datasetId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper);
        ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
        service = new RevalidationService(
                revalidationTaskRepository,
                testCaseRepository,
                testSuiteRepository,
                datasetRepository,
                testCaseValidationService,
                suiteValidationService,
                testSuiteMetricDefinitionService,
                jsonbMapper,
                revalidationProperties,
                warningsSerializer,
                schemaChangeCoercer,
                clock);

        lenient().when(revalidationProperties.getBatchSize()).thenReturn(50);
        lenient().when(revalidationProperties.getTimeoutMinutes()).thenReturn(5);
    }

    // -----------------------------------------------------------------------
    // startDatasetRevalidation — entry point validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("startDatasetRevalidation throws EntityNotFoundException when dataset does not exist")
    void startThrowsWhenDatasetMissing() {
        when(datasetRepository.existsById(datasetId)).thenReturn(false);

        assertThatThrownBy(() -> service.startDatasetRevalidation(datasetId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(datasetId.toString());

        verify(revalidationTaskRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "startDatasetRevalidation persists PENDING task with totalCases from countByDatasetId and returns the DTO")
    void startPersistsPendingTask() {
        when(datasetRepository.existsById(datasetId)).thenReturn(true);
        when(testCaseRepository.countByDatasetId(datasetId)).thenReturn(0L);
        // Dataset lookup is part of the async invocation, returning empty stops Phase 1/2 cleanly
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());
        when(revalidationTaskRepository.save(any())).thenAnswer(inv -> {
            RevalidationTask t = inv.getArgument(0);
            t.setId(taskId);
            return t;
        });
        when(revalidationTaskRepository.findById(taskId))
                .thenAnswer(inv -> Optional.of(RevalidationTask.builder()
                        .id(taskId)
                        .datasetId(datasetId)
                        .status(RevalidationStatus.PENDING.name())
                        .totalCases(0)
                        .build()));

        RevalidationTaskDto dto = service.startDatasetRevalidation(datasetId);

        assertThat(dto.getDatasetId()).isEqualTo(datasetId);
        assertThat(dto.getStatus()).isEqualTo(RevalidationStatus.PENDING);
        assertThat(dto.getTotalCases()).isEqualTo(0);

        ArgumentCaptor<RevalidationTask> taskCap = ArgumentCaptor.forClass(RevalidationTask.class);
        verify(revalidationTaskRepository).save(taskCap.capture());
        assertThat(taskCap.getValue().getStatus()).isEqualTo(RevalidationStatus.PENDING.name());
        assertThat(taskCap.getValue().getDatasetId()).isEqualTo(datasetId);
    }

    // -----------------------------------------------------------------------
    // runDatasetRevalidationAsync — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "happy path: Phase 1 processes all test cases, Phase 2 runs per suite, task transitions PENDING → RUNNING → COMPLETED")
    void happyPathCompletesTask() {
        RevalidationTask task = pendingTask(2);
        when(revalidationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema("[]").build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        TestCase tc1 = testCase("tc1", 100L);
        TestCase tc2 = testCase("tc2", 100L);
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(tc1, tc2));
        lenient()
                .when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(2), anyInt()))
                .thenReturn(List.of());
        when(schemaChangeCoercer.coerceMap(any(), any())).thenReturn(new CoercionResult(new HashMap<>(), 0, false));
        when(testCaseValidationService.validateTestCase(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(testCaseRepository.updateValidationIfUnchanged(
                        any(), eq(datasetId), anyBoolean(), any(), anyLong(), anyLong()))
                .thenReturn(1);

        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        TestSuite tsA = TestSuite.builder().id(suiteA).responseColumns("[]").build();
        TestSuite tsB = TestSuite.builder().id(suiteB).responseColumns("[]").build();
        when(testSuiteRepository.findSuitesReferencingDataset(datasetId)).thenReturn(List.of(tsA, tsB));
        when(suiteValidationService.validateSuite(any(TestSuite.class), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        // Per-suite Phase 2 work for both
        verify(testSuiteMetricDefinitionService).revalidateAllForSuite(eq(suiteA), any(), any());
        verify(testSuiteMetricDefinitionService).revalidateAllForSuite(eq(suiteB), any(), any());
        verify(testSuiteRepository).updateValidation(eq(suiteA), eq(true), any(), anyLong());
        verify(testSuiteRepository).updateValidation(eq(suiteB), eq(true), any(), anyLong());

        // The task is mutated in place across multiple update() calls — the final state
        // captured is the COMPLETED status with full counts (ArgumentCaptor stores references).
        verify(revalidationTaskRepository, atLeast(2)).update(any());
        assertThat(task.getStatus()).isEqualTo(RevalidationStatus.COMPLETED.name());
        assertThat(task.getValidCount()).isEqualTo(2);
        assertThat(task.getInvalidCount()).isEqualTo(0);
        assertThat(task.getProcessedCases()).isEqualTo(2);
        assertThat(task.getCompletedAtMs()).isEqualTo(clock.millis());
    }

    // -----------------------------------------------------------------------
    // Phase 2 — per-suite resilience
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Phase 2: a thrown exception during one suite's revalidation does NOT abort other suites")
    void phase2_oneSuiteFailure_doesNotAbortOthers() {
        // No test cases — skip Phase 1 entirely
        RevalidationTask task = pendingTask(0);
        when(revalidationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema("[]").build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        UUID suiteC = UUID.randomUUID();
        TestSuite tsA = TestSuite.builder().id(suiteA).responseColumns("[]").build();
        TestSuite tsB = TestSuite.builder().id(suiteB).responseColumns("[]").build();
        TestSuite tsC = TestSuite.builder().id(suiteC).responseColumns("[]").build();
        when(testSuiteRepository.findSuitesReferencingDataset(datasetId)).thenReturn(List.of(tsA, tsB, tsC));

        // suiteA succeeds, suiteB blows up inside suiteValidationService, suiteC succeeds
        when(suiteValidationService.validateSuite(eq(tsA), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(suiteValidationService.validateSuite(eq(tsB), any())).thenThrow(new RuntimeException("boom-on-suiteB"));
        when(suiteValidationService.validateSuite(eq(tsC), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(false)
                        .warnings(List.of())
                        .build());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        // suiteA and suiteC are fully revalidated despite suiteB throwing
        verify(testSuiteMetricDefinitionService).revalidateAllForSuite(eq(suiteA), any(), any());
        verify(testSuiteMetricDefinitionService).revalidateAllForSuite(eq(suiteC), any(), any());
        verify(testSuiteRepository).updateValidation(eq(suiteA), eq(true), any(), anyLong());
        verify(testSuiteRepository).updateValidation(eq(suiteC), eq(false), any(), anyLong());

        // suiteB skipped past the throw — no metric-def revalidation, no updateValidation call
        verify(testSuiteMetricDefinitionService, never()).revalidateAllForSuite(eq(suiteB), any(), any());
        verify(testSuiteRepository, never()).updateValidation(eq(suiteB), anyBoolean(), any(), anyLong());

        // The dataset-level task still ends COMPLETED — per-suite failures are isolated and logged
        verify(revalidationTaskRepository, atLeast(1)).update(any());
        assertThat(task.getStatus()).isEqualTo(RevalidationStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("Phase 2: an exception inside revalidateAllForSuite does NOT abort other suites")
    void phase2_metricRevalidationFailure_doesNotAbortOthers() {
        RevalidationTask task = pendingTask(0);
        when(revalidationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema("[]").build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        UUID suiteA = UUID.randomUUID();
        UUID suiteB = UUID.randomUUID();
        TestSuite tsA = TestSuite.builder().id(suiteA).responseColumns("[]").build();
        TestSuite tsB = TestSuite.builder().id(suiteB).responseColumns("[]").build();
        when(testSuiteRepository.findSuitesReferencingDataset(datasetId)).thenReturn(List.of(tsA, tsB));

        when(suiteValidationService.validateSuite(any(TestSuite.class), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());

        // suiteA's metric-def revalidation throws — suiteB still runs
        org.mockito.Mockito.doThrow(new RuntimeException("metric-boom"))
                .when(testSuiteMetricDefinitionService)
                .revalidateAllForSuite(eq(suiteA), any(), any());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        // suiteB completed normally
        verify(testSuiteMetricDefinitionService).revalidateAllForSuite(eq(suiteB), any(), any());
        verify(testSuiteRepository).updateValidation(eq(suiteB), eq(true), any(), anyLong());
        // suiteA's updateValidation was skipped because metric-def reval threw before reaching it
        verify(testSuiteRepository, never()).updateValidation(eq(suiteA), anyBoolean(), any(), anyLong());
    }

    // -----------------------------------------------------------------------
    // Phase 1 — concurrent edit handling
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Phase 1: when data was concurrently edited (updateDataIfUnchanged returns 0), test case is skipped")
    void phase1_concurrentDataEdit_skipsTestCase() {
        RevalidationTask task = pendingTask(1);
        when(revalidationTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        Dataset dataset = Dataset.builder().id(datasetId).testCaseSchema("[]").build();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        TestCase tc = testCase("tc", 100L);
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(tc));
        lenient()
                .when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        // Coercion changed → updateDataIfUnchanged is called, returns 0 (concurrent edit) → skip
        Map<String, Object> coerced = new HashMap<>();
        coerced.put("x", 1);
        when(schemaChangeCoercer.coerceMap(any(), any())).thenReturn(new CoercionResult(coerced, 1, true));
        when(testCaseRepository.updateDataIfUnchanged(any(), eq(datasetId), any(), anyLong(), anyLong()))
                .thenReturn(0);

        when(testSuiteRepository.findSuitesReferencingDataset(datasetId)).thenReturn(List.of());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        // Validation update is never reached because the coerce-write guard failed
        verify(testCaseRepository, never())
                .updateValidationIfUnchanged(any(), any(), anyBoolean(), any(), anyLong(), anyLong());
        verify(testCaseValidationService, never()).validateTestCase(any(), any(), any(), any(), anyBoolean(), any());
    }

    // -----------------------------------------------------------------------
    // Async failure path — task ends FAILED on unexpected exception
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("runDatasetRevalidationAsync: when dataset disappears after task save, task ends FAILED with reason")
    void async_datasetGoneAfterStart_endsFailed() {
        RevalidationTask task = pendingTask(0);
        AtomicInteger counter = new AtomicInteger(0);
        when(revalidationTaskRepository.findById(taskId)).thenAnswer(inv -> {
            int c = counter.getAndIncrement();
            return Optional.of(
                    c == 0
                            ? task
                            : RevalidationTask.builder()
                                    .id(taskId)
                                    .datasetId(datasetId)
                                    .status(RevalidationStatus.RUNNING.name())
                                    .build());
        });
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        ArgumentCaptor<RevalidationTask> cap = ArgumentCaptor.forClass(RevalidationTask.class);
        verify(revalidationTaskRepository, atLeast(2)).update(cap.capture());
        RevalidationTask last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(RevalidationStatus.FAILED.name());
        assertThat(last.getErrorMessage()).contains("Dataset not found");
        assertThat(last.getCompletedAtMs()).isEqualTo(clock.millis());
    }

    @Test
    @DisplayName("runDatasetRevalidationAsync: when task id does not resolve, no updates are issued")
    void async_taskIdMissing_silentlyReturns() {
        when(revalidationTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        service.runDatasetRevalidationAsync(taskId, datasetId);

        verify(revalidationTaskRepository, never()).update(any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RevalidationTask pendingTask(int total) {
        return RevalidationTask.builder()
                .id(taskId)
                .datasetId(datasetId)
                .status(RevalidationStatus.PENDING.name())
                .totalCases(total)
                .processedCases(0)
                .validCount(0)
                .invalidCount(0)
                .build();
    }

    private TestCase testCase(String name, long updatedAt) {
        return TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName(name)
                .data("{}")
                .updatedAt(updatedAt)
                .build();
    }

    // Local alias to avoid importing org.mockito.ArgumentMatchers.anyBoolean below
    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
