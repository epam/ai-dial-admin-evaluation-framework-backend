package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.AsyncConfiguration;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.query.service.QueryDslRunnableTestCaseSelector;
import com.epam.aidial.evaluation.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.EvaluationTraceSession;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.SuiteSnapshotBuilder;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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
    private MetricScoreComputationExecutor metricScoreComputation;

    @Mock
    private Clock clock;

    @Mock
    private PlatformTransactionManager metaTransactionManager;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private ActiveRunRegistry registry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestSuiteEvaluationJob job;
    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk telemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
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
                metricScoreComputation,
                clock,
                metaTransactionManager,
                taskExecutor,
                registry,
                telemetry);
    }

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
        MDC.remove(SecurityConstants.CORRELATION_ID_MDC_KEY);
    }

    private SpanData runSpan() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(TracingConstants.SPAN_EVAL_RUN))
                .findFirst()
                .orElseThrow();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName(
            "dispatch preserves native span context, baggage and correlation ID on the configured virtual/platform executor")
    void dispatchPropagatesNativeContext(boolean virtual) throws Exception {
        final UUID runId = UUID.randomUUID();
        final RunHandle handle = new RunHandle(Executors.newVirtualThreadPerTaskExecutor());
        handle.cancel();
        when(registry.registerIfAbsent(runId)).thenReturn(Optional.of(handle));
        final CompletableFuture<SpanContext> observedSpan = new CompletableFuture<>();
        final CompletableFuture<String> observedBaggage = new CompletableFuture<>();
        final CompletableFuture<String> observedCorrelation = new CompletableFuture<>();
        doAnswer(inv -> {
                    observedSpan.complete(Span.current().getSpanContext());
                    observedBaggage.complete(Baggage.current().getEntryValue("probe"));
                    observedCorrelation.complete(MDC.get(SecurityConstants.CORRELATION_ID_MDC_KEY));
                    return 1;
                })
                .when(repository)
                .updateToCancelled(eq(runId), anyLong(), anyLong());
        final Span parent = telemetry.getTracer("test").spanBuilder("incoming").startSpan();
        final Context parentContext = Context.current()
                .with(parent)
                .with(Baggage.builder().put("probe", "retained").build());
        try (SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor)
                new AsyncConfiguration().testSuiteRunExecutor(new RunExecutorFactory(virtual))) {
            ReflectionTestUtils.setField(job, "taskExecutor", executor);
            MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "originalCorrelation");
            try (Scope ignored = parentContext.makeCurrent()) {
                job.dispatch(runId, null, true);
            }
            assertThat(observedSpan.get(5, TimeUnit.SECONDS).getTraceId())
                    .isEqualTo(parent.getSpanContext().getTraceId());
            assertThat(observedBaggage.get(5, TimeUnit.SECONDS)).isEqualTo("retained");
            assertThat(observedCorrelation.get(5, TimeUnit.SECONDS)).isEqualTo("originalCorrelation");
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(exporter.getFinishedSpanItems()).hasSize(1));
            assertThat(runSpan().getParentSpanId())
                    .isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_ID)))
                    .isEqualTo(runId.toString());
        } finally {
            parent.end();
        }
    }

    @Test
    @DisplayName("dispatch restores the previous native context and correlation ID when the submitted job throws")
    void dispatchRestoresContextWhenJobThrows() {
        final UUID runId = UUID.randomUUID();
        final RunHandle handle = new RunHandle(Executors.newVirtualThreadPerTaskExecutor());
        handle.cancel();
        when(registry.registerIfAbsent(runId)).thenReturn(Optional.of(handle));
        doThrow(new IllegalStateException("terminal write failed"))
                .when(repository)
                .updateToCancelled(eq(runId), anyLong(), anyLong());
        final Span original =
                telemetry.getTracer("test").spanBuilder("original").startSpan();
        final Span previous =
                telemetry.getTracer("test").spanBuilder("previous").startSpan();
        final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "originalCorrelation");
        try (Scope ignored = Context.current()
                .with(original)
                .with(Baggage.builder().put("probe", "original").build())
                .makeCurrent()) {
            job.dispatch(runId, null, true);
        }
        verify(taskExecutor).execute(task.capture());
        MDC.put(SecurityConstants.CORRELATION_ID_MDC_KEY, "previousCorrelation");
        try (Scope ignored = Context.current()
                .with(previous)
                .with(Baggage.builder().put("probe", "previous").build())
                .makeCurrent()) {
            assertThatThrownBy(() -> task.getValue().run()).isInstanceOf(IllegalStateException.class);
            assertThat(Span.current().getSpanContext()).isEqualTo(previous.getSpanContext());
            assertThat(Baggage.current().getEntryValue("probe")).isEqualTo("previous");
            assertThat(MDC.get(SecurityConstants.CORRELATION_ID_MDC_KEY)).isEqualTo("previousCorrelation");
            assertThat(runSpan().getParentSpanId())
                    .isEqualTo(original.getSpanContext().getSpanId());
        } finally {
            original.end();
            previous.end();
        }
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
        @DisplayName(
                "buildContext exposes the snapshot's request chain (requestName + additionalRequests) via the suite")
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
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);

            EvaluationContext context = (EvaluationContext) ReflectionTestUtils.invokeMethod(
                    job,
                    "buildContext",
                    run,
                    invokeResolveSnapshot(run),
                    Executors.newVirtualThreadPerTaskExecutor(),
                    CallerCredential.bearer("token"));

            final List<RequestExecutionSpec> chain = ((HttpSuite) context.getSuite()).chain();
            assertThat(chain.getFirst().name()).isEqualTo("first");
            assertThat(chain.subList(1, chain.size()))
                    .extracting(
                            RequestExecutionSpec::name,
                            RequestExecutionSpec::endpointRef,
                            RequestExecutionSpec::requestTemplate)
                    .containsExactlyElementsOf(additionalRequests.stream()
                            .map(r -> tuple(r.getName(), r.getEndpointRef(), r.getRequestTemplate()))
                            .toList());
        }

        @Test
        @DisplayName("buildContext yields a single-request chain with null requestName for a legacy snapshot")
        void buildContextSingleRequestChainForLegacySnapshot() {
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
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);

            EvaluationContext context = (EvaluationContext) ReflectionTestUtils.invokeMethod(
                    job,
                    "buildContext",
                    run,
                    invokeResolveSnapshot(run),
                    Executors.newVirtualThreadPerTaskExecutor(),
                    CallerCredential.bearer("token"));

            final List<RequestExecutionSpec> chain = ((HttpSuite) context.getSuite()).chain();
            assertThat(chain.getFirst().name()).isNull();
            assertThat(chain).hasSize(1);
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
                    Executors.newVirtualThreadPerTaskExecutor());

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
                    Executors.newVirtualThreadPerTaskExecutor());

            assertThat(context.requestLabelAt(0)).isNull();
            assertThat(context.requestLabelAt(1)).isNull();
        }
    }

    @Nested
    @DisplayName("buildMetricEvaluationContext — overallScoreDefinition fallback")
    class OverallScoreDefinitionFallback {

        @Test
        @DisplayName("prefers testCaseOverallScore over overallScore when both are configured")
        void prefersTestCaseOverallScoreWhenPresent() throws Exception {
            Mean overallScore = new Mean();
            Mean testCaseOverallScore = new Mean();
            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .overallScore(overallScore)
                    .testCaseOverallScore(testCaseOverallScore)
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
                    Executors.newVirtualThreadPerTaskExecutor());

            assertThat(context.getOverallScoreDefinition()).isEqualTo(testCaseOverallScore);
        }

        @Test
        @DisplayName("falls back to overallScore when testCaseOverallScore is absent")
        void fallsBackToOverallScoreWhenTestCaseOverallScoreAbsent() throws Exception {
            Mean overallScore = new Mean();
            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .overallScore(overallScore)
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
                    Executors.newVirtualThreadPerTaskExecutor());

            assertThat(context.getOverallScoreDefinition()).isEqualTo(overallScore);
        }

        @Test
        @DisplayName("does not fall back to a CustomFunction overallScore when testCaseOverallScore is absent "
                + "(population-dependent functions are meaningless per test case; TestSuiteRequestValidator "
                + "already rejects a CustomFunction testCaseOverallScore outright)")
        void doesNotFallBackToCustomFunctionOverallScore() throws Exception {
            CustomFunction overallScore = new CustomFunction(Map.of("entity", "eval_summaries", "mode", "aggregate"));
            SuiteSnapshotDto snapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .overallScore(overallScore)
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
                    Executors.newVirtualThreadPerTaskExecutor());

            assertThat(context.getOverallScoreDefinition()).isNull();
        }
    }

    @Nested
    @DisplayName("buildContext — SuiteTarget derivation")
    class TargetWiring {

        private EvaluationContext buildFor(SuiteSnapshotDto snapshot) {
            final EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
            execution.setDefaultConcurrencyLevel(1);
            execution.setDefaultRequestTimeoutMs(1000L);
            execution.setResultBatchSize(10);
            execution.setMaxResponseSizeBytes(1000L);
            final EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);
            final TestSuiteRun run = TestSuiteRun.builder()
                    .id(UUID.randomUUID())
                    .testSuiteId(UUID.randomUUID())
                    .numberOfTestCases(1)
                    .createdAt(1000L)
                    .build();
            return (EvaluationContext) ReflectionTestUtils.invokeMethod(
                    job,
                    "buildContext",
                    run,
                    snapshot,
                    Executors.newVirtualThreadPerTaskExecutor(),
                    CallerCredential.bearer("token"));
        }

        @Test
        @DisplayName("DEPLOYMENT snapshot yields a DeploymentTarget carrying the deployment ref")
        void deploymentSnapshot() {
            final DeploymentReferenceDto ref =
                    DeploymentReferenceDto.builder().id("gpt-4").build();
            final EvaluationContext context = buildFor(SuiteSnapshotDto.builder()
                    .suiteType("DEPLOYMENT")
                    .deploymentRef(ref)
                    .build());

            assertThat(context.getTarget()).isEqualTo(new DeploymentTarget(ref));
        }

        @Test
        @DisplayName("legacy snapshot without suiteType yields a DeploymentTarget")
        void legacySnapshotDefaultsToDeployment() {
            final EvaluationContext context =
                    buildFor(SuiteSnapshotDto.builder().build());

            assertThat(context.getTarget()).isInstanceOf(DeploymentTarget.class);
        }

        @Test
        @DisplayName("ROUTE snapshot yields a RouteTarget carrying the route ref")
        void routeSnapshot() {
            final RouteReferenceDto ref = RouteReferenceDto.builder()
                    .name("routes/platform/validate-route")
                    .build();
            final EvaluationContext context = buildFor(
                    SuiteSnapshotDto.builder().suiteType("ROUTE").routesRef(ref).build());

            assertThat(context.getTarget()).isEqualTo(new RouteTarget(ref));
        }

        @Test
        @DisplayName("MCP_TOOL snapshot yields a McpToolTarget carrying the MCP deployment and tool refs")
        void mcpSnapshot() {
            final McpDeploymentReferenceDto mcpRef =
                    McpDeploymentReferenceDto.builder().id("mcp-1").build();
            final ToolReferenceDto toolRef =
                    ToolReferenceDto.builder().name("search").build();
            final EvaluationContext context = buildFor(SuiteSnapshotDto.builder()
                    .suiteType("MCP_TOOL")
                    .mcpDeploymentRef(mcpRef)
                    .toolRef(toolRef)
                    .build());

            assertThat(context.getTarget()).isEqualTo(new McpToolTarget(mcpRef, toolRef));
        }
    }

    @Nested
    @DisplayName("run(...) — job orchestration (design D4)")
    class Run {

        private UUID runId;
        private UUID suiteId;
        private UUID datasetId;
        private TestSuiteRun run;
        private TestSuite liveSuite;
        private Dataset liveDataset;
        private RunHandle handle;

        @BeforeEach
        void setUp() {
            runId = UUID.randomUUID();
            suiteId = UUID.randomUUID();
            datasetId = UUID.randomUUID();
            run = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .createdAt(1000L)
                    .suiteSnapshot(null)
                    .build();

            liveSuite = TestSuite.builder()
                    .id(suiteId)
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .build();
            liveDataset = Dataset.builder().id(datasetId).build();
            handle = new RunHandle(Executors.newVirtualThreadPerTaskExecutor());
        }

        /** Stubs the snapshot phase (and legacy snapshot resolution) so the run reaches Phase 1/2. */
        private void stubResolvableRun() {
            SuiteSnapshotDto builtSnapshot = SuiteSnapshotDto.builder()
                    .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                    .suiteType("DEPLOYMENT")
                    .build();
            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.of(liveSuite));
            when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(liveDataset));
            when(suiteSnapshotBuilder.build(liveSuite, liveDataset)).thenReturn(builtSnapshot);
        }

        /** Stubs {@code evaluationRunProperties} so {@code buildContext} (Phase 1) does not NPE. */
        private void stubExecutionRunProperties() {
            EvaluationRunProperties.Execution execution = new EvaluationRunProperties.Execution();
            execution.setDefaultConcurrencyLevel(1);
            execution.setDefaultRequestTimeoutMs(1000L);
            execution.setResultBatchSize(10);
            execution.setMaxResponseSizeBytes(1000L);
            EvaluationRunProperties.Retry retry = new EvaluationRunProperties.Retry();
            retry.setDefaultMaxRetries(0);
            retry.setDefaultRetryDelayMs(100L);
            retry.setMaxRetryDelayMs(100L);
            retry.setDefaultRetryBackoffMultiplier(1.0);
            when(evaluationRunProperties.getExecution()).thenReturn(execution);
            when(evaluationRunProperties.getRetry()).thenReturn(retry);
        }

        @ParameterizedTest
        @ValueSource(strings = {"completed", "failed", "cancelled"})
        @DisplayName(
                "the same tracing session spans both phases and closes all roots on completed, failed and cancelled exits")
        void sessionIsSharedAndAlwaysClosed(String outcome) {
            stubResolvableRun();
            stubExecutionRunProperties();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            if (outcome.equals("completed")) {
                when(repository.updateToCompleted(eq(runId), anyLong(), anyLong()))
                        .thenReturn(1);
            }
            final UUID caseId = UUID.randomUUID();
            final AtomicReference<EvaluationTraceSession> seen = new AtomicReference<>();
            doAnswer(inv -> {
                        final EvaluationContext context = inv.getArgument(0);
                        seen.set(context.getTraceSession());
                        context.getTraceSession()
                                .startSpan(telemetry.getTracer("test").spanBuilder("execution"), caseId, "case", 0)
                                .end();
                        return null;
                    })
                    .when(evaluationExecutor)
                    .execute(any());
            doAnswer(inv -> {
                        final MetricEvaluationContext context = inv.getArgument(0);
                        assertThat(context.getTraceSession()).isSameAs(seen.get());
                        context.getTraceSession()
                                .startSpan(telemetry.getTracer("test").spanBuilder("metric"), caseId, "case", 0)
                                .end();
                        if (outcome.equals("failed")) {
                            throw new AnalyticsWriteException("failed", new IllegalStateException("DB"));
                        }
                        if (outcome.equals("cancelled")) {
                            handle.cancel();
                            throw new RejectedExecutionException("cancelled");
                        }
                        return null;
                    })
                    .when(metricEvaluationExecutor)
                    .execute(any());
            job.run(runId, null, false, handle);
            assertThat(exporter.getFinishedSpanItems().stream()
                            .filter(s -> s.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE))
                            .toList())
                    .hasSize(1);
            assertThat(seen.get()
                            .startSpan(telemetry.getTracer("test").spanBuilder("late"), caseId, "case", 0)
                            .getSpanContext()
                            .isValid())
                    .isFalse();
            assertThat(ReflectionTestUtils.getField(seen.get(), "roots")).isEqualTo(Map.of());
            assertThat(handle.executor().isShutdown()).isTrue();
        }

        @Test
        @DisplayName("runs Phase 2 + Phase 3 but never Phase 1, and completes the run")
        void runsPhase2And3NeverPhase1() {
            stubResolvableRun();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            when(repository.updateToCompleted(eq(runId), anyLong(), anyLong())).thenReturn(1);

            job.run(runId, null, true, handle);

            verify(evaluationExecutor, never()).execute(any());
            verify(metricEvaluationExecutor).execute(any());
            verify(metricScoreComputation).execute(any());
            verify(repository).updateToRunning(eq(runId), anyLong(), anyLong());
            verify(repository).updateToCompleted(eq(runId), anyLong(), anyLong());
            verify(repository, never()).updateToCancelled(any(), anyLong(), anyLong());
            verify(repository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
            verify(repository).updateSuiteSnapshot(eq(runId), any(), anyLong());
            verify(testCaseRunInputRepository, never()).insertBatch(any());
            verify(runnableTestCaseSelector, never()).loadRunnablePage(any(), any(), anyInt(), anyInt());
            verify(repository, never()).updateNumberOfTestCases(any(), anyInt(), anyLong());
            verify(registry).remove(runId);

            assertThat(runSpan().getParentSpanContext().isValid()).isFalse();
            assertThat(runSpan().getSpanContext().isValid()).isTrue();
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_STATUS)))
                    .isEqualTo("COMPLETED");
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_SUITE_ID)))
                    .isEqualTo(suiteId.toString());
        }

        @Test
        @DisplayName("registry.remove is invoked after the terminal notifySse, not before")
        void registryRemoveInvokedAfterNotifySse() {
            stubResolvableRun();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            when(repository.updateToCompleted(eq(runId), anyLong(), anyLong())).thenReturn(1);

            job.run(runId, null, true, handle);

            final InOrder inOrder = inOrder(sseService, registry);
            // Two notifySse calls happen on this path: once after updateToRunning, once as the
            // terminal notification in the finally block. registry.remove must come after both.
            inOrder.verify(sseService, times(2)).notifyStatusUpdate(any());
            inOrder.verify(registry).remove(runId);
        }

        @Test
        @DisplayName("shouldExitWithoutRunning_whenCancelledWhilePending")
        void shouldExitWithoutRunning_whenCancelledWhilePending() {
            stubResolvableRun();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(0);

            job.run(runId, null, true, handle);

            verify(evaluationExecutor, never()).execute(any());
            verify(metricEvaluationExecutor, never()).execute(any());
            verify(metricScoreComputation, never()).execute(any());
            verify(repository, never()).updateToCompleted(any(), anyLong(), anyLong());
            verify(repository, never()).updateToCancelled(any(), anyLong(), anyLong());
            verify(repository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
            verify(registry).remove(runId);
            verify(sseService, never()).notifyStatusUpdate(any());
        }

        @Test
        @DisplayName("shouldSkipSnapshot_whenHandleCancelledBeforeStart")
        void shouldSkipSnapshot_whenHandleCancelledBeforeStart() {
            handle.cancel();

            job.run(runId, null, true, handle);

            verify(repository, never()).updateToRunning(any(), anyLong(), anyLong());
            verify(repository, never()).updateSuiteSnapshot(any(), any(), anyLong());
            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
            verify(registry).remove(runId);
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_STATUS)))
                    .isEqualTo("CANCELLED");
            assertThat(runSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        }

        @Test
        @DisplayName("shouldMarkCancelledAndSkipPhase2And3_whenCancelledDuringPhase1")
        void shouldMarkCancelledAndSkipPhase2And3_whenCancelledDuringPhase1() {
            stubResolvableRun();
            stubExecutionRunProperties();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            doThrow(new RejectedExecutionException("run executor shut down"))
                    .when(evaluationExecutor)
                    .execute(any());

            job.run(runId, null, false, handle);

            verify(evaluationExecutor).execute(any());
            verify(metricEvaluationExecutor, never()).execute(any());
            verify(metricScoreComputation, never()).execute(any());
            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
            verify(repository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("shouldMarkCancelled_whenCompletedWriteAffectsNoRows")
        void shouldMarkCancelled_whenCompletedWriteAffectsNoRows() {
            stubResolvableRun();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            when(repository.updateToCompleted(eq(runId), anyLong(), anyLong())).thenReturn(0);

            job.run(runId, null, true, handle);

            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
            verify(repository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("shouldMarkFailedAnalyticsWriteFailed_whenPhase2FlushThrows_evenIfCancelled")
        void shouldMarkFailedAnalyticsWriteFailed_whenPhase2FlushThrows_evenIfCancelled() {
            stubResolvableRun();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            doAnswer(invocation -> {
                        handle.cancel();
                        throw new AnalyticsWriteException("flush failed", new RuntimeException("db down"));
                    })
                    .when(metricEvaluationExecutor)
                    .execute(any());

            job.run(runId, null, true, handle);

            ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).updateToFailed(eq(runId), any(), detailsCaptor.capture(), anyLong(), anyLong());
            assertThat(detailsCaptor.getValue()).contains(AnalyticsWriteException.ERROR_CODE);
            assertThat(runSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_STATUS)))
                    .isEqualTo("FAILED");
            verify(metricScoreComputation, never()).execute(any());
            verify(repository, never()).updateToCancelled(any(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("shouldNotifySseExactlyOnce_whenSnapshotPhaseFails")
        void shouldNotifySseExactlyOnce_whenSnapshotPhaseFails() {
            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());
            when(repository.updateToFailed(eq(runId), any(), any(), anyLong(), anyLong()))
                    .thenReturn(1);

            job.run(runId, null, true, handle);

            verify(repository).updateToFailed(eq(runId), any(), any(), anyLong(), anyLong());
            verify(sseService, times(1)).notifyStatusUpdate(any());
            verify(registry).remove(runId);
            assertThat(runSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_SUITE_ID)))
                    .isEqualTo(suiteId.toString());
        }

        @Test
        @DisplayName("shouldNotNotifySse_whenSnapshotPhaseFailedWriteAffectsNoRows")
        void shouldNotNotifySse_whenSnapshotPhaseFailedWriteAffectsNoRows() {
            when(repository.findById(runId)).thenReturn(Optional.of(run));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());
            when(repository.updateToFailed(eq(runId), any(), any(), anyLong(), anyLong()))
                    .thenReturn(0);

            job.run(runId, null, true, handle);

            verify(repository).updateToFailed(eq(runId), any(), any(), anyLong(), anyLong());
            verify(sseService, never()).notifyStatusUpdate(any());
            verify(registry).remove(runId);
        }

        @ParameterizedTest
        @EnumSource(
                value = RunStatus.class,
                names = {"CANCELLED", "COMPLETED", "FAILED"})
        @DisplayName("a rejected snapshot failure write exports the persisted terminal outcome without notifying SSE")
        void rejectedSnapshotFailureUsesPersistedOutcome(RunStatus terminalStatus) {
            run.setStatus(RunStatus.PENDING.name());
            final TestSuiteRun terminal = TestSuiteRun.builder()
                    .id(runId)
                    .testSuiteId(suiteId)
                    .status(terminalStatus.name())
                    .build();
            when(repository.findById(runId)).thenReturn(Optional.of(run)).thenReturn(Optional.of(terminal));
            when(testSuiteRepository.findById(suiteId)).thenReturn(Optional.empty());
            when(repository.updateToFailed(eq(runId), any(), any(), anyLong(), anyLong()))
                    .thenReturn(0);

            job.run(runId, null, true, handle);

            assertThat(runSpan().getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_STATUS)))
                    .isEqualTo(terminalStatus.name());
            assertThat(runSpan().getStatus().getStatusCode())
                    .isEqualTo(terminalStatus == RunStatus.FAILED ? StatusCode.ERROR : StatusCode.UNSET);
            assertThat(runSpan().getEvents()).hasSize(1);
            verify(repository, times(2)).findById(runId);
            verify(repository).updateToFailed(eq(runId), any(), any(), anyLong(), anyLong());
            verify(sseService, never()).notifyStatusUpdate(any());
            verify(metricEvaluationExecutor, never()).execute(any());
            verify(registry).remove(runId);
        }

        @Test
        @DisplayName("shouldMarkCancelled_whenUnexpectedExceptionDuringPhase1AndHandleIsCancelled")
        void shouldMarkCancelled_whenUnexpectedExceptionDuringPhase1AndHandleIsCancelled() {
            stubResolvableRun();
            stubExecutionRunProperties();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            when(repository.updateToCancelled(eq(runId), anyLong(), anyLong())).thenReturn(1);
            doAnswer(invocation -> {
                        handle.cancel();
                        throw new IllegalStateException("boom");
                    })
                    .when(evaluationExecutor)
                    .execute(any());

            job.run(runId, null, false, handle);

            verify(repository).updateToCancelled(eq(runId), anyLong(), anyLong());
            verify(repository, never()).updateToFailed(any(), any(), any(), anyLong(), anyLong());
            verify(metricEvaluationExecutor, never()).execute(any());
        }

        @Test
        @DisplayName("shouldMarkFailedUnexpectedError_whenUnexpectedExceptionDuringPhase1AndHandleNotCancelled")
        void shouldMarkFailedUnexpectedError_whenUnexpectedExceptionDuringPhase1AndHandleNotCancelled() {
            stubResolvableRun();
            stubExecutionRunProperties();
            when(repository.updateToRunning(eq(runId), anyLong(), anyLong())).thenReturn(1);
            doThrow(new IllegalStateException("boom")).when(evaluationExecutor).execute(any());

            job.run(runId, null, false, handle);

            ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).updateToFailed(eq(runId), any(), detailsCaptor.capture(), anyLong(), anyLong());
            assertThat(detailsCaptor.getValue()).contains("UNEXPECTED_ERROR");
            assertThat(runSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            verify(repository, never()).updateToCancelled(any(), anyLong(), anyLong());
            verify(metricEvaluationExecutor, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("dispatch(...) — submission-time cleanup (configurable-thread-mode-run-executors design D4)")
    class Dispatch {

        @Test
        @DisplayName("cleans up and rethrows the Error when the executor throws at submission time")
        void cleansUpAndRethrowsErrorFromExecutorSubmission() {
            final UUID runId = UUID.randomUUID();
            final RunHandle handle = mock(RunHandle.class);
            when(registry.registerIfAbsent(runId)).thenReturn(Optional.of(handle));
            final OutOfMemoryError error = new OutOfMemoryError("unable to create native thread");
            doThrow(error).when(taskExecutor).execute(any());

            assertThatThrownBy(() -> job.dispatch(runId, null, true)).isSameAs(error);

            verify(registry).remove(runId);
            verify(handle).close();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }
    }
}
