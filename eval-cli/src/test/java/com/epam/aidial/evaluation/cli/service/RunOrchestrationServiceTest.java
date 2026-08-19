package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunOrchestrationServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private TestCaseRunnerFactory testCaseRunnerFactory;

    @Mock
    private EvaluationContextFactory evaluationContextFactory;

    @Mock
    private TestCaseRunInputMapper testCaseRunInputMapper;

    @Mock
    private TestCaseRunner testCaseRunner;

    @Mock
    private SuiteContractValidator suiteContractValidator;

    private RunOrchestrationService service;

    @BeforeEach
    void setUp() {
        service = new RunOrchestrationService(
                testCaseRunnerFactory, evaluationContextFactory, testCaseRunInputMapper, suiteContractValidator);
    }

    @Test
    @DisplayName("delegates to TestCaseRunner submit and awaitCompletion — does not reimplement concurrency")
    void delegatesToTestCaseRunner() throws Exception {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto tc = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .build();

        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("Suite")
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(tc))
                .build();

        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        final EvaluationContext context = EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(sourceSuiteId)
                .datasetId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .concurrencyLevel(4)
                .requestTimeoutMs(60000L)
                .maxRetries(0)
                .retryDelayMs(0L)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(0L)
                .resultBatchSize(10)
                .maxResponseSizeBytes(1048576L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("tok")
                .createdAtMs(0L)
                .snapshotDeploymentRef(targetRef)
                .snapshotResponseColumns(List.of())
                .snapshotInputBindings(List.of())
                .inputBindings(List.of())
                .build();

        final TestCaseRunInput input = TestCaseRunInput.builder()
                .testCaseId(tc.getId())
                .testCaseName("TC1")
                .build();

        when(evaluationContextFactory.create(any(), eq(1), eq(targetRef), any()))
                .thenReturn(context);
        when(testCaseRunInputMapper.toInput(any())).thenReturn(input);
        when(testCaseRunnerFactory.create(any(), any(), any())).thenReturn(testCaseRunner);

        final File csvFile = service.run(bundle, targetRef, tempDir.toString());

        // Verify the runner was used, not a custom loop
        verify(testCaseRunner).submit(anyList());
        verify(testCaseRunner).awaitCompletion();
        verify(suiteContractValidator).validate(suite);
        assertThat(csvFile).exists();
    }

    @Test
    @DisplayName("falls back to the suite's own recorded deploymentRef when --deployment-id is omitted")
    void fallsBackToSuiteDeploymentRefWhenTargetRefAbsent() throws Exception {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto tc = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .build();

        final DeploymentReferenceDto suiteDeploymentRef = DeploymentReferenceDto.builder()
                .id("suite-model")
                .name("Suite Model")
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("Suite")
                .deploymentRef(suiteDeploymentRef)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(tc))
                .build();

        final EvaluationContext context = EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(sourceSuiteId)
                .datasetId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .concurrencyLevel(4)
                .requestTimeoutMs(60000L)
                .maxRetries(0)
                .retryDelayMs(0L)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(0L)
                .resultBatchSize(10)
                .maxResponseSizeBytes(1048576L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("tok")
                .createdAtMs(0L)
                .snapshotDeploymentRef(suiteDeploymentRef)
                .snapshotResponseColumns(List.of())
                .snapshotInputBindings(List.of())
                .inputBindings(List.of())
                .build();

        final TestCaseRunInput input = TestCaseRunInput.builder()
                .testCaseId(tc.getId())
                .testCaseName("TC1")
                .build();

        when(evaluationContextFactory.create(any(), eq(1), eq(suiteDeploymentRef), any()))
                .thenReturn(context);
        when(testCaseRunInputMapper.toInput(any())).thenReturn(input);
        when(testCaseRunnerFactory.create(any(), any(), any())).thenReturn(testCaseRunner);

        final File csvFile = service.run(bundle, null, tempDir.toString());

        verify(evaluationContextFactory).create(suite, 1, suiteDeploymentRef, null);
        assertThat(csvFile).exists();
    }

    @Test
    @DisplayName("fails with a clear error when --deployment-id is omitted and the suite has no deploymentRef")
    void failsWhenTargetRefAndSuiteDeploymentRefBothAbsent() {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestSuiteResponseDto suite =
                TestSuiteResponseDto.builder().id(sourceSuiteId).name("Suite").build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of())
                .build();

        assertThatThrownBy(() -> service.run(bundle, null, tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no recorded deploymentRef")
                .hasMessageContaining("--deployment-id was not provided");

        verifyNoInteractions(evaluationContextFactory, testCaseRunnerFactory);
    }

    @Test
    @DisplayName("propagates SuiteContractValidator failures without invoking the runner")
    void propagatesContractValidationFailure() {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestSuiteResponseDto suite =
                TestSuiteResponseDto.builder().id(sourceSuiteId).name("Suite").build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of())
                .build();
        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        doThrow(new IllegalStateException("invalid contract"))
                .when(suiteContractValidator)
                .validate(suite);

        assertThatThrownBy(() -> service.run(bundle, targetRef, tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid contract");

        verifyNoInteractions(evaluationContextFactory, testCaseRunnerFactory);
    }

    // ────────────────────────────────────────────────────────────────────────────────
    // Guards — stale-bundle schema guard and MCP/multi-turn pre-flight guard
    // ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stale-bundle guard fires when schema is absent and a fetched test case is multi-turn")
    void staleBundleGuardFiresWithMultiTurnCasePresent() {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto multiTurnCase = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .multiTurnData(List.of(Map.of("prompt", "hi"), Map.of("prompt", "again")))
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("Suite")
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(multiTurnCase))
                .testCaseSchema(null)
                .build();
        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        assertThatThrownBy(() -> service.run(bundle, targetRef, tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fetch");

        verifyNoInteractions(evaluationContextFactory, testCaseRunnerFactory);
    }

    @Test
    @DisplayName("stale-bundle guard does not fire when no fetched test case is multi-turn")
    void staleBundleGuardDoesNotFireWithoutMultiTurnCases() throws Exception {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto singleTurnCase = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("Suite")
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(singleTurnCase))
                .testCaseSchema(null)
                .build();
        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        final EvaluationContext context = minimalContext(targetRef);
        when(evaluationContextFactory.create(any(), eq(1), eq(targetRef), any()))
                .thenReturn(context);
        when(testCaseRunInputMapper.toInput(any()))
                .thenReturn(TestCaseRunInput.builder()
                        .testCaseId(singleTurnCase.getId())
                        .testCaseName("TC1")
                        .build());
        when(testCaseRunnerFactory.create(any(), any(), any())).thenReturn(testCaseRunner);

        final File csvFile = service.run(bundle, targetRef, tempDir.toString());

        assertThat(csvFile).exists();
        verify(testCaseRunner).submit(anyList());
    }

    @Test
    @DisplayName("MCP suite with a multi-turn case is rejected pre-flight with no factory interaction")
    void mcpSuiteWithMultiTurnCaseRejectedPreFlight() {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto multiTurnCase = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .multiTurnData(List.of(Map.of("prompt", "hi"), Map.of("prompt", "again")))
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("MCP Suite")
                .suiteType(SuiteType.MCP_TOOL)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(multiTurnCase))
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build()))
                .build();
        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        assertThatThrownBy(() -> service.run(bundle, targetRef, tempDir.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP");

        verifyNoInteractions(evaluationContextFactory, testCaseRunnerFactory);
    }

    @Test
    @DisplayName("MCP suite without multi-turn cases proceeds normally")
    void mcpSuiteWithoutMultiTurnCasesProceeds() throws Exception {
        final UUID sourceSuiteId = UUID.randomUUID();
        final TestCaseResponseDto singleTurnCase = TestCaseResponseDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("TC1")
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(sourceSuiteId)
                .name("MCP Suite")
                .suiteType(SuiteType.MCP_TOOL)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();
        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(UUID.randomUUID())
                .suite(suite)
                .testCases(List.of(singleTurnCase))
                .build();
        final DeploymentReferenceDto targetRef =
                DeploymentReferenceDto.builder().id("target").name("Target").build();

        final EvaluationContext context = minimalContext(targetRef);
        when(evaluationContextFactory.create(any(), eq(1), eq(targetRef), any()))
                .thenReturn(context);
        when(testCaseRunInputMapper.toInput(any()))
                .thenReturn(TestCaseRunInput.builder()
                        .testCaseId(singleTurnCase.getId())
                        .testCaseName("TC1")
                        .build());
        when(testCaseRunnerFactory.create(any(), any(), any())).thenReturn(testCaseRunner);

        final File csvFile = service.run(bundle, targetRef, tempDir.toString());

        assertThat(csvFile).exists();
        verify(testCaseRunner).submit(anyList());
    }

    private EvaluationContext minimalContext(DeploymentReferenceDto targetRef) {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .concurrencyLevel(4)
                .requestTimeoutMs(60000L)
                .maxRetries(0)
                .retryDelayMs(0L)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(0L)
                .resultBatchSize(10)
                .maxResponseSizeBytes(1048576L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("tok")
                .createdAtMs(0L)
                .snapshotDeploymentRef(targetRef)
                .snapshotResponseColumns(List.of())
                .snapshotInputBindings(List.of())
                .inputBindings(List.of())
                .build();
    }
}
