package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.ConditionDecision;
import com.epam.aidial.evaluation.service.domain.ConditionExpressionEvaluator;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("InProcessMetricEvaluationExecutor")
@ExtendWith(MockitoExtension.class)
class InProcessMetricEvaluationExecutorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TestCaseRunResultRepository resultRepository;

    @Mock
    private MetricEvaluationWorker worker;

    // A spy over the real mapper, not a mock: the metric-less tests assert the {@code {}} /
    // {@code null} shape the mapper itself produces, which an unstubbed mock would report as
    // {@code null} and a stubbed one would make tautological.
    @Spy
    private MetricOutputMapper outputMapper = new MetricOutputMapper(new ObjectMapper());

    @Mock
    private EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;

    @Mock
    private RunMetricSnapshotBatchWriteClient runMetricSnapshotBatchWriteClient;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OutputSchemaFieldExtractor outputSchemaFieldExtractor;

    @Mock
    private ConditionExpressionEvaluator conditionExpressionEvaluator;

    @Mock
    private Clock clock;

    @InjectMocks
    private InProcessMetricEvaluationExecutor executor;

    @BeforeEach
    void stubConditions() {
        lenient().when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.run());
    }

    @Test
    @DisplayName("All TSMDs complete within perResultTimeoutMs — EvalSummary assembled from actual responses")
    void allTsmdsCompleteWithinTimeout_evalSummaryAssembledFromResponses() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Accuracy")
                .declarationProviderId("dial")
                .metricDeclarationName("exact_match")
                .build();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);

        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(resultId)
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();

        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));

        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName("exact_match")
                .output(Map.of(
                        "score",
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.ONE)
                                .build()))
                .build();
        when(worker.evaluate(eq(tsmd), eq(result), any(Semaphore.class), eq(context)))
                .thenReturn(response);
        when(clock.millis()).thenReturn(1_000L, 1_000L, 1_150L);

        ObjectNode values = objectMapper.createObjectNode();
        values.putObject("Accuracy").put("score", 1);
        doReturn(values).when(outputMapper).buildMetricValues(any());
        doReturn(null).when(outputMapper).buildMetricInfos(any());

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(items.get(0).getMetricValues()).isNotNull();
        assertThat(items.get(0).getAvgMetricEvalDurationMs()).isEqualTo(150L);
    }

    @Test
    @DisplayName("Slow TSMD exceeds perResultTimeoutMs — timed-out TSMDs recorded as FAILED")
    void slowTsmdExceedsTimeout_timedOutTsmdsRecordedAsFailed() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("SlowMetric")
                .declarationProviderId("dial")
                .metricDeclarationName("slow_eval")
                .build();

        // Set a very short timeout so the worker blocks past it
        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 100L);

        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(resultId)
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();

        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));

        // Worker blocks longer than the timeout
        doAnswer(invocation -> {
                    Thread.sleep(5000);
                    return null;
                })
                .when(worker)
                .evaluate(eq(tsmd), eq(result), any(Semaphore.class), eq(context));
        when(clock.millis()).thenReturn(5_000L, 5_000L, 5_300L);

        // After timeout, the executor records the TSMD as a TsmdEvaluationResult.Failure via putIfAbsent
        // The output mapper is mocked — verify the executor correctly produces FAILED status
        ObjectNode values = objectMapper.createObjectNode();
        values.putObject("SlowMetric").putNull("score");
        ObjectNode infos = objectMapper.createObjectNode();
        infos.putObject("SlowMetric")
                .putObject("score")
                .put("error", "Metric evaluation timed out for TSMD SlowMetric");
        doReturn(values).when(outputMapper).buildMetricValues(any());
        doReturn(infos).when(outputMapper).buildMetricInfos(any());

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(items.get(0).getAvgMetricEvalDurationMs())
                .as("a timed-out TSMD still contributes its real elapsed time (dispatch to timeout detection)")
                .isEqualTo(300L);
    }

    @Test
    @DisplayName("Condition false → metric not dispatched, result stays SUCCESS")
    void conditionFalse_metricNotDispatched_resultSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Accuracy")
                .declarationProviderId("dial")
                .metricDeclarationName("exact_match")
                .condition("turn.last")
                .build();
        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);

        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));

        ObjectNode emptyValues = objectMapper.createObjectNode();
        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.skip());
        doReturn(emptyValues).when(outputMapper).buildMetricValues(any());
        doReturn(null).when(outputMapper).buildMetricInfos(any());

        executor.execute(context);

        verify(worker, never()).evaluate(any(), any(), any(Semaphore.class), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());
        assertThat(captor.getValue().get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Condition error → metric not dispatched, result stays SUCCESS")
    void conditionError_metricNotDispatched_resultSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Accuracy")
                .declarationProviderId("dial")
                .metricDeclarationName("exact_match")
                .condition("response.score")
                .build();
        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);

        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));

        ObjectNode emptyValues = objectMapper.createObjectNode();
        ObjectNode infos = objectMapper.createObjectNode();
        when(conditionExpressionEvaluator.evaluate(any(), any()))
                .thenReturn(ConditionDecision.error("Condition did not evaluate to a boolean"));
        doReturn(emptyValues).when(outputMapper).buildMetricValues(any());
        doReturn(infos).when(outputMapper).buildMetricInfos(any());

        executor.execute(context);

        verify(worker, never()).evaluate(any(), any(), any(Semaphore.class), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());
        assertThat(captor.getValue().get(0).getExecutionStatus())
                .as("a broken condition must not fail the result row")
                .isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Failed TSMD call still contributes its elapsed time to the average")
    void failedTsmdCall_stillContributesElapsedTimeToAverage() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("FlakyMetric")
                .declarationProviderId("dial")
                .metricDeclarationName("flaky_eval")
                .build();
        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);

        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));

        when(worker.evaluate(eq(tsmd), eq(result), any(Semaphore.class), eq(context)))
                .thenThrow(new RuntimeException("transport failure"));
        when(clock.millis()).thenReturn(2_000L, 2_000L, 2_500L);

        ObjectNode values = objectMapper.createObjectNode();
        ObjectNode infos = objectMapper.createObjectNode();
        doReturn(values).when(outputMapper).buildMetricValues(any());
        doReturn(infos).when(outputMapper).buildMetricInfos(any());

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(items.get(0).getAvgMetricEvalDurationMs())
                .as("a failed TSMD call still contributes its real elapsed time, same as a successful one")
                .isEqualTo(500L);
    }

    @Test
    @DisplayName("computeAvgMetricEvalDurationMs excludes ConditionError entries and defaults to 0")
    void computeAvgMetricEvalDurationMs_excludesConditionErrorsAndDefaultsToZero() {
        EvaluationResponseDto response = EvaluationResponseDto.builder().build();

        Map<String, TsmdEvaluationResult> mixed = Map.of(
                "successMetric", new TsmdEvaluationResult.Success(response, List.of(), 100L),
                "failedMetric", new TsmdEvaluationResult.Failure(new RuntimeException("boom"), List.of(), 300L),
                "conditionErrorMetric", new TsmdEvaluationResult.ConditionError("bad condition", List.of()));

        assertThat(executor.computeAvgMetricEvalDurationMs(mixed))
                .as("average must be over Success/Failure only: (100 + 300) / 2")
                .isEqualTo(200L);

        assertThat(executor.computeAvgMetricEvalDurationMs(Map.of()))
                .as("no dispatched TSMDs defaults to 0")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("Empty TSMD list — one metric-less EvalSummary per result row and no metric work at all")
    void emptyTsmds_writeOneMetricLessSummaryPerResult() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(), 10000L);

        TestCaseRunResult first = successResult(runId, suiteId, "tc1");
        TestCaseRunResult second = successResult(runId, suiteId, "tc2");
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(first, second), null, false));

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.getMetricValues()).isNotNull();
            assertThat(item.getMetricValues().isEmpty()).isTrue();
            assertThat(item.getMetricInfos()).isNull();
        });

        // No snapshot rows: the client is still called, with an empty list it discards.
        verify(runMetricSnapshotBatchWriteClient).batchWrite(eq(runId), any(), any(), argThat(List::isEmpty));
        verifyNoInteractions(worker);
        verifyNoInteractions(conditionExpressionEvaluator);
    }

    @Test
    @DisplayName("Empty TSMD list — per-row status is preserved and every non-metric field is carried through")
    void emptyTsmds_preserveStatusAndPayload() {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(), 10000L);

        TestCaseRunResult success = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("turn-1-of-2")
                .runIndex(3)
                .turnIndex(1)
                .totalTurns(2)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{\"prompt\":\"hi\"}")
                .extractedColumns("{\"answer\":\"there\"}")
                .extractionWarnings("[\"missing: score\"]")
                .execDurationMs(42L)
                .responseStatusCode(200)
                .build();
        TestCaseRunResult failed = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("transport-failure")
                .runIndex(0)
                .executionStatus(ExecutionStatus.FAILED)
                .testCaseData("{}")
                .extractedColumns("{}")
                .execDurationMs(7L)
                .responseStatusCode(500)
                .build();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(success, failed), null, false));

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items)
                .extracting(EvalSummaryBatchWriteItemDto::getExecutionStatus)
                .containsExactly(ExecutionStatus.SUCCESS, ExecutionStatus.FAILED);

        EvalSummaryBatchWriteItemDto successItem = items.get(0);
        assertThat(successItem.getRunIndex()).isEqualTo(3);
        assertThat(successItem.getTurnIndex()).isEqualTo(1);
        assertThat(successItem.getTotalTurns()).isEqualTo(2);
        assertThat(successItem.getExecDurationMs()).isEqualTo(42L);
        assertThat(successItem.getResponseStatusCode()).isEqualTo(200);
        assertThat(successItem.getTestCaseData().get("prompt").asString()).isEqualTo("hi");
        assertThat(successItem.getExtractedColumns().get("answer").asString()).isEqualTo("there");
        assertThat(successItem.getExtractionWarnings().get(0).asString()).isEqualTo("missing: score");

        EvalSummaryBatchWriteItemDto failedItem = items.get(1);
        assertThat(failedItem.getExecDurationMs()).isEqualTo(7L);
        assertThat(failedItem.getResponseStatusCode()).isEqualTo(500);
    }

    private static TestCaseRunResult successResult(UUID runId, UUID suiteId, String testCaseName) {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName(testCaseName)
                .runIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();
    }

    private MetricEvaluationContext buildContext(
            UUID runId, UUID suiteId, List<AggregatedMetricDefinition> tsmds, long perResultTimeoutMs) {
        MetricEvaluationProperties.Retry retryConfig = new MetricEvaluationProperties.Retry();
        retryConfig.setMaxRetries(0);
        retryConfig.setRetryDelayMs(100L);
        retryConfig.setRetryBackoffMultiplier(1.0);
        retryConfig.setMaxRetryDelayMs(1000L);

        return MetricEvaluationContext.builder()
                .computationId(UUID.randomUUID())
                .computedAtMs(FIXED_CLOCK.millis())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .runCreatedAtMs(FIXED_CLOCK.millis())
                .aggregatedTsmds(tsmds)
                .cancellationSignal(new AtomicBoolean(false))
                .retryConfig(retryConfig)
                .defaultConcurrencyPerProvider(5)
                .batchSize(100)
                .perResultTimeoutMs(perResultTimeoutMs)
                .build();
    }
}
