package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteProperties;
import com.epam.aidial.evaluation.configuration.properties.testsuite.TestSuiteRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.analytics.EvalResultsCsvParser;
import com.epam.aidial.evaluation.service.domain.analytics.EvalResultsImportService;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.job.ExecutionSettingsValidator;
import com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.TestSuiteRunMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteRunService.importResultsAndEvaluate")
@ExtendWith(MockitoExtension.class)
class TestSuiteRunServiceTest {

    @Mock
    private TestSuiteRunRepository testSuiteRunRepository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestCaseService testCaseService;

    @Mock
    private RunnableTestCaseCounter runnableTestCaseCounter;

    @Mock
    private TestSuiteEvaluationJob evaluationJob;

    @Mock
    private ExecutionSettingsValidator executionSettingsValidator;

    @Mock
    private TestSuiteRunSseService sseService;

    @Mock
    private TestSuiteRunMapper mapper;

    @Mock
    private FilterParser filterParser;

    @Mock
    private SortParser sortParser;

    @Mock
    private EvalResultsImportService evalResultsImportService;

    @Mock
    private EvalResultsCsvParser evalResultsCsvParser;

    private TestSuiteRunService service;
    private UUID testSuiteId;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        TestSuiteRunProperties.Limits limits = new TestSuiteRunProperties.Limits();
        limits.setMaxConcurrentRunsGlobal(100);
        limits.setMaxConcurrentRunsPerSuite(100);
        TestSuiteRunProperties properties = new TestSuiteRunProperties();
        properties.setLimits(limits);

        service = new TestSuiteRunService(
                testSuiteRunRepository,
                testSuiteRepository,
                testCaseService,
                runnableTestCaseCounter,
                properties,
                evaluationJob,
                executionSettingsValidator,
                sseService,
                mapper,
                filterParser,
                sortParser,
                new ObjectMapper(),
                evalResultsImportService,
                evalResultsCsvParser,
                new ChainNormalizer(new JsonbMapper(new ObjectMapper())),
                new ChainConfigurationValidator(chainProperties()));

        testSuiteId = UUID.randomUUID();
        datasetId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private TestSuite validBoundSuite() {
        return TestSuite.builder()
                .id(testSuiteId)
                .datasetId(datasetId)
                .valid(true)
                .responseColumns("[]")
                .build();
    }

    /** Minimal CSV stream — content not important since parsing is mocked via {@code evalResultsCsvParser}. */
    private InputStream emptyCsvStream() {
        return new ByteArrayInputStream("testCaseName,runIndex\n".getBytes(StandardCharsets.UTF_8));
    }

    private List<TestCaseRunResult> oneItem(String testCaseName) {
        return List.of(TestCaseRunResult.builder()
                .testCaseName(testCaseName)
                .testCaseId(java.util.UUID.randomUUID())
                .runIndex(0)
                .testCaseData("{}")
                .executionStatus(ExecutionStatus.SUCCESS)
                .execStartedAtMs(1000L)
                .execCompletedAtMs(1500L)
                .build());
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("throws EntityNotFoundException when suite does not exist")
        void throwsWhenSuiteNotFound() {
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, null, emptyCsvStream(), 0L, ','))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("throws DatasetVisibilityRuleException when suite has no dataset")
        void throwsWhenSuiteUnbound() {
            TestSuite suite = TestSuite.builder()
                    .id(testSuiteId)
                    .datasetId(null)
                    .valid(true)
                    .build();
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(suite));

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, null, emptyCsvStream(), 0L, ','))
                    .isInstanceOf(DatasetVisibilityRuleException.class);
        }

        @Test
        @DisplayName("throws InvalidOperationException when suite is not valid")
        void throwsWhenSuiteInvalid() {
            TestSuite suite = TestSuite.builder()
                    .id(testSuiteId)
                    .datasetId(datasetId)
                    .valid(false)
                    .build();
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(suite));

            assertThatThrownBy(() -> service.importResultsAndEvaluate(testSuiteId, null, emptyCsvStream(), 0L, ','))
                    .isInstanceOf(InvalidOperationException.class);
        }
    }

    @Nested
    @DisplayName("afterCommit dispatch")
    class AfterCommitDispatch {

        @BeforeEach
        void setUpResolvableSuite() {
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(validBoundSuite()));
            when(evalResultsCsvParser.parse(eq(datasetId), any(InputStream.class), anyLong(), anyChar()))
                    .thenReturn(oneItem("tc1"));
            when(testSuiteRunRepository.nextRunNameSequenceValue()).thenReturn(1L);
            when(testSuiteRunRepository.save(any(TestSuiteRun.class))).thenAnswer(invocation -> {
                TestSuiteRun run = invocation.getArgument(0);
                run.setId(UUID.randomUUID());
                return run;
            });
            when(mapper.toDto(any(TestSuiteRun.class)))
                    .thenReturn(TestSuiteRunResponseDto.builder().build());
        }

        private void invokeAndFireAfterCommit() {
            TransactionSynchronizationManager.initSynchronization();
            service.importResultsAndEvaluate(testSuiteId, null, emptyCsvStream(), 0L, ',');
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
        }

        @Test
        @DisplayName("dispatches Phase 2+3 evaluation when result persistence succeeds, passing parsed items through")
        void dispatchesEvaluationOnSuccess() {
            invokeAndFireAfterCommit();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TestCaseRunResult>> itemsCaptor = ArgumentCaptor.forClass(List.class);
            verify(evalResultsImportService)
                    .persistResults(eq(testSuiteId), any(TestSuiteRun.class), itemsCaptor.capture());
            assertThat(itemsCaptor.getValue()).hasSize(1);
            assertThat(itemsCaptor.getValue().get(0).getTestCaseName()).isEqualTo("tc1");

            verify(evaluationJob).registerCancellationSignal(any(UUID.class));
            verify(evaluationJob).executeRunAsync(any(UUID.class), isNull(), eq(true));
            verify(testSuiteRunRepository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("marks the run FAILED and never dispatches evaluation when result persistence fails")
        void marksRunFailedWhenPersistenceFails() {
            doThrow(new RuntimeException("analytics write failed"))
                    .when(evalResultsImportService)
                    .persistResults(eq(testSuiteId), any(TestSuiteRun.class), any());
            when(testSuiteRunRepository.findById(any(UUID.class)))
                    .thenReturn(Optional.of(
                            TestSuiteRun.builder().id(UUID.randomUUID()).build()));

            invokeAndFireAfterCommit();

            verify(testSuiteRunRepository)
                    .updateToFailed(
                            any(UUID.class),
                            eq("Failed to persist imported eval results"),
                            any(),
                            anyLong(),
                            anyLong());
            verify(sseService).notifyStatusUpdate(any(TestSuiteRun.class));
            verify(evaluationJob, never()).executeRunAsync(any(), any(), anyBoolean());
            verify(evaluationJob, never()).registerCancellationSignal(any());
        }
    }

    @Nested
    @DisplayName("createRun — chain cap re-check (guard 3b)")
    class ChainCapGuard {

        @Test
        @DisplayName("a suite saved under a higher cap is rejected once the cap is lowered")
        void overCapChainRejectedAfterCapLowered() {
            // The suite was saved when the cap allowed 3 requests; the deployment has since lowered it to 2.
            TestSuiteRunService serviceWithLoweredCap = serviceWithCap(2);

            assertThatThrownBy(() -> serviceWithLoweredCap.createRun(testSuiteId, null))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("chain of 3 requests")
                    .hasMessageContaining("maximum of 2");

            // Guard 3b precedes the runnable-count query, so the cheaper configuration check reports first.
            verify(runnableTestCaseCounter, never()).countRunnable(any(), any(), any());
            verify(evaluationJob, never()).executeRunAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a chain exactly at the current cap passes the guard and reaches the runnable-count query")
        void chainAtCapPassesGuard() {
            TestSuiteRunService serviceAtCap = serviceWithCap(3);
            when(runnableTestCaseCounter.countRunnable(eq(datasetId), isNull(), any()))
                    .thenReturn(0L);

            // Zero runnable test cases is guard 4 — reaching it proves the chain cap guard let the run through.
            assertThatThrownBy(() -> serviceAtCap.createRun(testSuiteId, null))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("no valid and enabled test cases");
        }

        /**
         * A service whose only difference from the shared one is the configured chain cap, wired to a suite
         * carrying a 3-request chain. The cap is read at run creation, not captured at save, which is exactly
         * what makes lowering it able to reject an already-saved suite.
         */
        private TestSuiteRunService serviceWithCap(int maxRequests) {
            TestSuiteProperties props = new TestSuiteProperties();
            props.getMultiRequest().setMaxRequests(maxRequests);

            TestSuite suite = validBoundSuite();
            suite.setAdditionalRequests("[{\"label\":\"invoke\"},{\"label\":\"measure\"}]");
            when(testSuiteRepository.findById(testSuiteId)).thenReturn(Optional.of(suite));

            TestSuiteRunProperties.Limits limits = new TestSuiteRunProperties.Limits();
            limits.setMaxConcurrentRunsGlobal(100);
            limits.setMaxConcurrentRunsPerSuite(100);
            TestSuiteRunProperties runProperties = new TestSuiteRunProperties();
            runProperties.setLimits(limits);

            return new TestSuiteRunService(
                    testSuiteRunRepository,
                    testSuiteRepository,
                    testCaseService,
                    runnableTestCaseCounter,
                    runProperties,
                    evaluationJob,
                    executionSettingsValidator,
                    sseService,
                    mapper,
                    filterParser,
                    sortParser,
                    new ObjectMapper(),
                    evalResultsImportService,
                    evalResultsCsvParser,
                    new ChainNormalizer(new JsonbMapper(new ObjectMapper())),
                    new ChainConfigurationValidator(props));
        }
    }

    /** Real properties instance with the production default chain cap, so chain validation behaves as shipped. */
    private static TestSuiteProperties chainProperties() {
        TestSuiteProperties props = new TestSuiteProperties();
        props.getMultiRequest().setMaxRequests(10);
        return props;
    }
}
