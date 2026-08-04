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

import com.epam.aidial.evaluation.cli.client.source.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.TestCaseRunner;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
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

        when(evaluationContextFactory.create(any(), eq(1), eq(targetRef))).thenReturn(context);
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
}
