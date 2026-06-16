package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

        executor.execute(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(eq(suiteId), eq(runId), any(), any(), captor.capture());

        List<EvalSummaryBatchWriteItemDto> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
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
