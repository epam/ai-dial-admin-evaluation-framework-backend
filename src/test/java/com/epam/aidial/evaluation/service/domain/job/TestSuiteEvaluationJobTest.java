package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.query.service.QueryDslRunnableTestCaseSelector;
import com.epam.aidial.evaluation.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilder;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteEvaluationJob")
@ExtendWith(MockitoExtension.class)
class TestSuiteEvaluationJobTest {

    @Mock
    private TestSuiteRunRepository repository;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private QueryDslRunnableTestCaseSelector runnableTestCaseSelector;

    @Mock
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Mock
    private TestSuiteRunSseService sseService;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private SuiteSnapshotBuilder suiteSnapshotBuilder;

    @Mock
    private EvaluationExecutor evaluationExecutor;

    @Mock
    private TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;

    @Mock
    private MetricEvaluationProperties metricEvaluationProperties;

    @Mock
    private MetricEvaluationExecutor metricEvaluationExecutor;

    @Mock
    private RunMetricSnapshotBatchWriteClient runMetricSnapshotBatchWriteClient;

    @Mock
    private MetricScoreComputationExecutor metricScoreComputation;

    @Mock
    private InlineModeDetector inlineModeDetector;

    @Mock
    private InlineMetricEvaluatorFactory inlineMetricEvaluatorFactory;

    @Mock
    private Clock clock;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestSuiteEvaluationJob job;

    @BeforeEach
    void setUp() {
        job = new TestSuiteEvaluationJob(
                repository,
                testSuiteRepository,
                datasetRepository,
                runnableTestCaseSelector,
                testCaseRunInputRepository,
                sseService,
                evaluationRunProperties,
                objectMapper,
                suiteSnapshotBuilder,
                evaluationExecutor,
                testSuiteMetricDefinitionService,
                metricEvaluationProperties,
                metricEvaluationExecutor,
                runMetricSnapshotBatchWriteClient,
                metricScoreComputation,
                inlineModeDetector,
                inlineMetricEvaluatorFactory,
                clock,
                metaTransactionManager);
    }

    @Nested
    @DisplayName("resolveSnapshot (via buildContext)")
    class ResolveSnapshot {

        @Test
        @DisplayName(
                "deserializes persisted snapshot when suite_snapshot is non-null and version matches CURRENT_VERSION")
        void deserializesPersistedSnapshot() throws Exception {
            SuiteSnapshotDto expected = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();
            String snapshotJson = objectMapper.writeValueAsString(expected);

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(result.getSuiteType()).isEqualTo("DEPLOYMENT");
        }

        @Test
        @DisplayName("throws UnsupportedSnapshotVersionException when persisted snapshot has older version")
        void throwsForLegacyVersionOneSnapshot() {
            // Legacy v1 snapshots predate the dataset entity. CURRENT_VERSION is "2"; the resolver
            // rejects any version that is not equal to CURRENT_VERSION.
            String snapshotJson = """
                    {"snapshotVersion":"1","suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(UnsupportedSnapshotVersionException.class)
                    .hasMessageContaining("1");
        }

        @Test
        @DisplayName("defaults missing snapshotVersion to CURRENT_VERSION during deserialization")
        void defaultsMissingSnapshotVersionToCurrentVersion() {
            // JSON without snapshotVersion field — resolver defaults to CURRENT_VERSION
            String snapshotJson = """
                    {"suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(result.getSuiteType()).isEqualTo("DEPLOYMENT");
        }

        @Test
        @DisplayName("throws UnsupportedSnapshotVersionException for unknown snapshot version")
        void throwsForUnknownSnapshotVersion() {
            String snapshotJson = """
                    {"snapshotVersion":"99","suiteType":"DEPLOYMENT"}
                    """;

            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(snapshotJson)
                    .build();

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(UnsupportedSnapshotVersionException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName(
                "synthesizes transient snapshot from live (suite, dataset) when suite_snapshot is null (legacy run)")
        void synthesizesSnapshotForLegacyRunWithNullSnapshot() {
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset liveDataset = Dataset.builder()
                    .id(datasetId)
                    .name("Legacy Dataset")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);

            SuiteSnapshotDto result = invokeResolveSnapshot(run);

            assertThat(result).isEqualTo(builtSnapshot);
            verify(suiteSnapshotBuilder).build(liveSuite, liveDataset);
        }

        @Test
        @DisplayName("throws SnapshotSuiteMissingException when legacy run references deleted suite")
        void throwsSnapshotSuiteMissingWhenLegacySuiteDeleted() {
            UUID suiteId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(SnapshotSuiteMissingException.class)
                    .hasMessageContaining(suiteId.toString());
        }

        @Test
        @DisplayName("throws SnapshotDatasetMissingException when legacy run references suite whose dataset is gone")
        void throwsSnapshotDatasetMissingWhenLegacyDatasetDeleted() {
            UUID runId = UUID.randomUUID();
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> invokeResolveSnapshot(run))
                    .isInstanceOf(SnapshotDatasetMissingException.class)
                    .hasMessageContaining(datasetId.toString());
        }
    }

    private SuiteSnapshotDto invokeResolveSnapshot(TestSuiteRun run) {
        return (SuiteSnapshotDto) ReflectionTestUtils.invokeMethod(job, "resolveSnapshot", run);
    }

    @Nested
    @DisplayName("buildContext / buildMetricEvaluationContext — request chain wiring")
    class RequestChainWiring {

        @Test
        @DisplayName("buildContext exposes snapshotAdditionalRequests and snapshotRequestName from the snapshot")
        void buildContextExposesChain() {
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(suiteId)
                    .numberOfTestCases(1)
                    .createdAt(1000L)
                    .suiteSnapshot(null)
                    .build();

            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();
            Dataset liveDataset = Dataset.builder().id(datasetId).build();

            List<RequestDefinitionDto> additionalRequests =
                    List.of(RequestDefinitionDto.builder().name("second").build());
            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .requestName("first")
                    .additionalRequests(additionalRequests)
                    .build();

            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);

            EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
            execution.setDefaultConcurrencyLevel(1);
            execution.setDefaultRequestTimeoutMs(1000L);
            execution.setResultBatchSize(10);
            execution.setMaxResponseSizeBytes(1000L);
            execution.setCancellationGracePeriodMs(1000L);
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);

            EvaluationContext context = (EvaluationContext) ReflectionTestUtils.invokeMethod(
                    job, "buildContext", run, invokeResolveSnapshot(run), new AtomicBoolean(false), "token", null);

            assertThat(context.getSnapshotRequestName()).isEqualTo("first");
            assertThat(context.getSnapshotAdditionalRequests()).isEqualTo(additionalRequests);
        }

        @Test
        @DisplayName("buildContext yields an empty chain and null requestName for a legacy snapshot")
        void buildContextEmptyChainForLegacySnapshot() {
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .numberOfTestCases(1)
                    .createdAt(1000L)
                    .suiteSnapshot("{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}")
                    .build();

            EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
            execution.setDefaultConcurrencyLevel(1);
            execution.setDefaultRequestTimeoutMs(1000L);
            execution.setResultBatchSize(10);
            execution.setMaxResponseSizeBytes(1000L);
            execution.setCancellationGracePeriodMs(1000L);
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);

            EvaluationContext context = (EvaluationContext) ReflectionTestUtils.invokeMethod(
                    job, "buildContext", run, invokeResolveSnapshot(run), new AtomicBoolean(false), "token", null);

            assertThat(context.getSnapshotRequestName()).isNull();
            assertThat(context.getSnapshotAdditionalRequests()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("buildMetricEvaluationContext exposes ordered request labels; out-of-range resolves to null")
        void buildMetricEvaluationContextExposesRequestLabels() throws Exception {
            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .requestName("first")
                    .additionalRequests(List.of(
                            RequestDefinitionDto.builder().name("second").build()))
                    .build();
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot(objectMapper.writeValueAsString(snapshot))
                    .build();

            MetricEvaluationContext context = (MetricEvaluationContext) ReflectionTestUtils.invokeMethod(
                    job,
                    "buildMetricEvaluationContext",
                    run,
                    invokeResolveSnapshot(run),
                    new AtomicBoolean(false),
                    false);

            assertThat(context.requestLabelAt(0)).isEqualTo("first");
            assertThat(context.requestLabelAt(1)).isEqualTo("second");
            assertThat(context.requestLabelAt(2)).isNull();
            assertThat(context.requestLabelAt(-1)).isNull();
        }

        @Test
        @DisplayName("buildMetricEvaluationContext resolves a null label for an unlabelled legacy chain")
        void buildMetricEvaluationContextNullLabelForLegacyChain() {
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .suiteSnapshot("{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}")
                    .build();

            MetricEvaluationContext context = (MetricEvaluationContext) ReflectionTestUtils.invokeMethod(
                    job,
                    "buildMetricEvaluationContext",
                    run,
                    invokeResolveSnapshot(run),
                    new AtomicBoolean(false),
                    false);

            assertThat(context.requestLabelAt(0)).isNull();
            assertThat(context.requestLabelAt(1)).isNull();
        }
    }

    @Nested
    @DisplayName("executeRunAsync(skipDeploymentPhase=true)")
    class ExecuteRunAsyncSkipDeploymentPhase {

        private UUID runId;
        private UUID suiteId;
        private UUID datasetId;
        private TestSuiteRun run;
        private TestSuite liveSuite;
        private Dataset liveDataset;

        @BeforeEach
        void setUp() {
            runId = UUID.randomUUID();
            suiteId = UUID.randomUUID();
            datasetId = UUID.randomUUID();
            run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .suiteSnapshot(null)
                    .build();

            liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();
            liveDataset = Dataset.builder().id(datasetId).build();

            // Used by buildMetricEvaluationContext (via buildMetricContextAndWriteSnapshot), which now
            // runs for real as part of the job itself rather than inside a mocked executor. Not every
            // test in this class reaches that code path, hence lenient.
            lenient()
                    .when(testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(any()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("runs Phase 2 + Phase 3 but never Phase 1, and completes the run")
        void runsPhase2And3NeverPhase1() {
            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();
            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);

            job.executeRunAsync(runId, null, true);

            verify(evaluationExecutor, never()).execute(any());
            verify(metricEvaluationExecutor).execute(any());
            verify(metricScoreComputation).execute(any());
            verify(repository).updateToRunning(eq(runId), anyLong(), anyLong());
            verify(repository).updateToCompleted(eq(runId), anyLong(), anyLong());
            verify(repository).updateSuiteSnapshot(eq(runId), any(), anyLong());
            verify(testCaseRunInputRepository, never()).insertBatch(any());
            verify(runnableTestCaseSelector, never()).loadRunnablePage(any(), any(), anyInt(), anyInt());
            verify(repository, never()).updateNumberOfTestCases(any(), anyInt(), anyLong());
            // Metric context is built and its run_metric_snapshots row written before Phase 2 runs, not
            // by the (mocked) metricEvaluationExecutor itself — an empty TSMD list still writes the
            // (empty) batch, same as InProcessMetricEvaluationExecutor did before the hoist.
            verify(runMetricSnapshotBatchWriteClient).batchWrite(eq(runId), any(), any(), argThat(List::isEmpty));
            // skipDeploymentPhase = true forces inlineMode = false unconditionally, without ever
            // invoking InlineModeDetector (per the "Inline metric evaluation mode is derived per run"
            // requirement's skipDeploymentPhase ⇒ non-inline rule).
            verifyNoInteractions(inlineModeDetector, inlineMetricEvaluatorFactory);
            ArgumentCaptor<MetricEvaluationContext> metricContextCaptor =
                    ArgumentCaptor.forClass(MetricEvaluationContext.class);
            verify(metricEvaluationExecutor).execute(metricContextCaptor.capture());
            assertThat(metricContextCaptor.getValue().isInlineMode()).isFalse();
        }

        @Test
        @DisplayName("cancellation before Phase 2 skips both metric evaluation and score computation")
        void cancellationSkipsPhase2And3() {
            job.registerCancellationSignal(runId);
            job.interruptRun(runId);

            job.executeRunAsync(runId, null, true);

            verify(metricEvaluationExecutor, never()).execute(any());
            verify(metricScoreComputation, never()).execute(any());
            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
        }

        @Test
        @DisplayName("cancellation during Phase 2 skips Phase 3 and cancels the run")
        void cancellationDuringPhase2SkipsPhase3() {
            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();
            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);
            job.registerCancellationSignal(runId);
            doAnswer(invocation -> {
                        job.interruptRun(runId);
                        return null;
                    })
                    .when(metricEvaluationExecutor)
                    .execute(any());

            job.executeRunAsync(runId, null, true);

            verify(metricEvaluationExecutor).execute(any());
            verify(metricScoreComputation, never()).execute(any());
            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("executeRunAsync(skipDeploymentPhase=false) — inline mode wiring")
    class InlineModeWiring {

        private UUID runId;
        private TestSuiteRun run;

        @BeforeEach
        void setUp() {
            runId = UUID.randomUUID();
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .numberOfTestCases(0)
                    .createdAt(1000L)
                    .suiteSnapshot("{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}")
                    .build();

            // executeRunAsync's snapshot phase always re-captures a fresh snapshot from the live
            // (suite, dataset) pair before checking the inconsistent-snapshot guard, regardless of
            // whether `run` already carries a suite_snapshot.
            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();
            Dataset liveDataset = Dataset.builder().id(datasetId).build();
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset))
                    .thenReturn(SuiteSnapshotDto.builder()
                            .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                            .suiteType("DEPLOYMENT")
                            .build());

            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testCaseRunInputRepository.existsByRunId(runId)).thenReturn(true);
            lenient()
                    .when(testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(any()))
                    .thenReturn(List.of());

            EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
            execution.setDefaultConcurrencyLevel(1);
            execution.setDefaultRequestTimeoutMs(1000L);
            execution.setResultBatchSize(10);
            execution.setMaxResponseSizeBytes(1000L);
            execution.setCancellationGracePeriodMs(1000L);
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            lenient().when(evaluationRunProperties.getExecution()).thenReturn(execution);
            lenient().when(evaluationRunProperties.getRetry()).thenReturn(retry);
        }

        @Test
        @DisplayName("inline run: factory-built evaluator is wired into EvaluationContext, flushed"
                + " immediately after Phase 1 returns (before Phase 2), and closed in the job's finally block")
        void inlineRun_wiresEvaluatorAndFlushesBeforePhase2() {
            when(inlineModeDetector.isInline(any(), any())).thenReturn(true);
            InlineMetricEvaluatorImpl inlineEvaluator = Mockito.mock(InlineMetricEvaluatorImpl.class);
            when(inlineMetricEvaluatorFactory.create(any())).thenReturn(inlineEvaluator);

            job.executeRunAsync(runId, "token", false);

            ArgumentCaptor<EvaluationContext> contextCaptor = ArgumentCaptor.forClass(EvaluationContext.class);
            verify(evaluationExecutor).execute(contextCaptor.capture());
            assertThat(contextCaptor.getValue().getInlineMetricEvaluator()).isSameAs(inlineEvaluator);

            InOrder order = Mockito.inOrder(evaluationExecutor, inlineEvaluator, metricEvaluationExecutor);
            order.verify(evaluationExecutor).execute(any());
            order.verify(inlineEvaluator).flush();
            order.verify(metricEvaluationExecutor).execute(any());
            verify(inlineEvaluator).close();
        }

        @Test
        @DisplayName("non-inline run: the factory is never invoked and EvaluationContext carries a null evaluator")
        void nonInlineRun_neverInvokesFactory() {
            when(inlineModeDetector.isInline(any(), any())).thenReturn(false);

            job.executeRunAsync(runId, "token", false);

            verify(inlineMetricEvaluatorFactory, never()).create(any());
            ArgumentCaptor<EvaluationContext> contextCaptor = ArgumentCaptor.forClass(EvaluationContext.class);
            verify(evaluationExecutor).execute(contextCaptor.capture());
            assertThat(contextCaptor.getValue().getInlineMetricEvaluator()).isNull();
        }
    }

    @Nested
    @DisplayName("executeRunAsync(skipDeploymentPhase=false) — inconsistent-snapshot guard")
    class InconsistentSnapshotGuard {

        @Test
        @DisplayName("run failing the inconsistent-snapshot guard writes no run_metric_snapshots row")
        void guardFailureWritesNoSnapshot() {
            UUID runId = UUID.randomUUID();
            UUID suiteId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();

            // suite_snapshot present but no test_case_run_inputs — the inconsistent combination.
            TestSuiteRun run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .suiteSnapshot("{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}")
                    .build();
            TestSuite liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();
            Dataset liveDataset = Dataset.builder().id(datasetId).build();

            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset))
                    .thenReturn(SuiteSnapshotDto.builder()
                            .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                            .suiteType("DEPLOYMENT")
                            .build());
            when(testCaseRunInputRepository.existsByRunId(runId)).thenReturn(false);

            job.executeRunAsync(runId, "token", false);

            verify(repository).updateToFailed(eq(runId), any(), any(), anyLong(), anyLong());
            verify(evaluationExecutor, never()).execute(any());
            verify(metricEvaluationExecutor, never()).execute(any());
            verify(metricScoreComputation, never()).execute(any());
            verify(runMetricSnapshotBatchWriteClient, never()).batchWrite(any(), any(), any(), any());
            verify(testSuiteMetricDefinitionService, never()).findAllEnabledAndValidAggregatedByTestSuiteId(any());
        }
    }
}
