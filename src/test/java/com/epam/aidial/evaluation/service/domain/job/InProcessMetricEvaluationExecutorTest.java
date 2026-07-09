package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.service.domain.ConditionContext;
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

@DisplayName("InProcessMetricEvaluationExecutor — per-result timeout")
@ExtendWith(MockitoExtension.class)
class InProcessMetricEvaluationExecutorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TestCaseRunResultRepository resultRepository;

    @Mock
    private MetricEvaluationWorker worker;

    @Mock
    private MetricOutputMapper outputMapper;

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

    @InjectMocks
    private InProcessMetricEvaluationExecutor executor;

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

        ObjectNode values = objectMapper.createObjectNode();
        values.putObject("Accuracy").put("score", 1);
        when(outputMapper.buildMetricValues(any())).thenReturn(values);
        when(outputMapper.buildMetricInfos(any())).thenReturn(null);
        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.run());

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(items.get(0).getMetricValues()).isNotNull();
    }

    @Test
    @DisplayName(
            "Condition context carries the result row's turn position, and the summary inherits turn_index/total_turns")
    void conditionContextAndSummaryCarryTurnPosition() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Accuracy")
                .declarationProviderId("dial")
                .metricDeclarationName("exact_match")
                .build();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);

        // A single per-turn result row: turn 2 of a 3-turn conversation.
        TestCaseRunResult result = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc1")
                .runIndex(0)
                .turnIndex(2)
                .totalTurns(3)
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();

        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));
        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName("exact_match")
                .output(Map.of())
                .build();
        when(worker.evaluate(eq(tsmd), eq(result), any(Semaphore.class), eq(context)))
                .thenReturn(response);
        ObjectNode emptyValues = objectMapper.createObjectNode();
        when(outputMapper.buildMetricValues(any())).thenReturn(emptyValues);
        when(outputMapper.buildMetricInfos(any())).thenReturn(null);

        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.run());

        executor.execute(context);

        // The condition is evaluated against the row's turn position.
        ArgumentCaptor<ConditionContext> conditionCaptor = ArgumentCaptor.forClass(ConditionContext.class);
        verify(conditionExpressionEvaluator).evaluate(any(), conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().turnIndex()).isEqualTo(2);
        assertThat(conditionCaptor.getValue().totalTurns()).isEqualTo(3);

        // The produced summary item inherits the same turn position.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());
        EvalSummaryBatchWriteItemDto item = captor.getValue().get(0);
        assertThat(item.getTurnIndex()).isEqualTo(2);
        assertThat(item.getTotalTurns()).isEqualTo(3);
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

        // After timeout, the executor records the TSMD as a TsmdEvaluationResult.Failure via putIfAbsent
        // The output mapper is mocked — verify the executor correctly produces FAILED status
        ObjectNode values = objectMapper.createObjectNode();
        values.putObject("SlowMetric").putNull("score");
        ObjectNode infos = objectMapper.createObjectNode();
        infos.putObject("SlowMetric")
                .putObject("score")
                .put("error", "Metric evaluation timed out for TSMD SlowMetric");
        when(outputMapper.buildMetricValues(any())).thenReturn(values);
        when(outputMapper.buildMetricInfos(any())).thenReturn(infos);
        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.run());

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("Condition false — metric not dispatched and omitted from results, summary stays SUCCESS")
    void conditionFalse_metricOmittedAndSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Relevancy")
                .declarationProviderId("dial")
                .metricDeclarationName("relevancy")
                .condition("$exists(response.answer)")
                .build();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);
        TestCaseRunResult result = successResult(runId, suiteId);

        ObjectNode emptyValues = objectMapper.createObjectNode();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));
        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.skip());
        when(outputMapper.buildMetricValues(any())).thenReturn(emptyValues);
        when(outputMapper.buildMetricInfos(any())).thenReturn(null);

        executor.execute(context);

        verify(worker, never()).evaluate(any(), any(), any(Semaphore.class), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, TsmdEvaluationResult>> resultsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outputMapper).buildMetricValues(resultsCaptor.capture());
        assertThat(resultsCaptor.getValue()).doesNotContainKey("Relevancy");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());
        assertThat(captor.getValue().get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("Condition error — ConditionError recorded, metric not dispatched, summary stays SUCCESS")
    void conditionError_recordedAndSuccess() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID suiteId = UUID.randomUUID();

        AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Relevancy")
                .declarationProviderId("dial")
                .metricDeclarationName("relevancy")
                .condition("response.answer")
                .build();

        MetricEvaluationContext context = buildContext(runId, suiteId, List.of(tsmd), 10000L);
        TestCaseRunResult result = successResult(runId, suiteId);

        ObjectNode emptyValues = objectMapper.createObjectNode();
        when(resultRepository.findAll(any(), any(), any(), eq(100)))
                .thenReturn(new CursorPage<>(List.of(result), null, false));
        when(conditionExpressionEvaluator.evaluate(any(), any())).thenReturn(ConditionDecision.error("boom"));
        when(outputMapper.buildMetricValues(any())).thenReturn(emptyValues);
        when(outputMapper.buildMetricInfos(any())).thenReturn(null);

        executor.execute(context);

        verify(worker, never()).evaluate(any(), any(), any(Semaphore.class), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, TsmdEvaluationResult>> resultsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outputMapper).buildMetricValues(resultsCaptor.capture());
        assertThat(resultsCaptor.getValue().get("Relevancy")).isInstanceOf(TsmdEvaluationResult.ConditionError.class);
        assertThat(((TsmdEvaluationResult.ConditionError)
                                resultsCaptor.getValue().get("Relevancy"))
                        .message())
                .isEqualTo("boom");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());
        assertThat(captor.getValue().get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    private TestCaseRunResult successResult(UUID runId, UUID suiteId) {
        return TestCaseRunResult.builder()
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
