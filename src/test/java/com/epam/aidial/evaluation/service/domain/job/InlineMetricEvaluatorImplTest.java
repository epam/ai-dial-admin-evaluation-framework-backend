package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricErrorDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.runner.job.InlineMetricRequest;
import com.epam.aidial.evaluation.runner.job.InlineMetricResult;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("InlineMetricEvaluatorImpl")
@ExtendWith(MockitoExtension.class)
class InlineMetricEvaluatorImplTest {

    @Mock
    private MetricRowEvaluator metricRowEvaluator;

    @Mock
    private EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private InlineMetricEvaluatorImpl newEvaluator(MetricEvaluationContext context) {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        return new InlineMetricEvaluatorImpl(
                context, metricRowEvaluator, evalSummaryBatchWriteClient, objectMapper, Map.of(), executor);
    }

    private MetricEvaluationContext context(int batchSize, AtomicBoolean cancellationSignal) {
        return MetricEvaluationContext.builder()
                .computationId(UUID.randomUUID())
                .computedAtMs(1_000L)
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .runCreatedAtMs(1_000L)
                .aggregatedTsmds(List.of())
                .cancellationSignal(cancellationSignal)
                .retryConfig(new MetricEvaluationProperties.Retry())
                .defaultConcurrencyPerProvider(5)
                .batchSize(batchSize)
                .perResultTimeoutMs(10_000L)
                .inlineMode(true)
                .build();
    }

    private TestCaseRunResult row() {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .testCaseData("{}")
                .extractedColumns("{}")
                .build();
    }

    private EvalSummaryBatchWriteItemDto item() {
        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .metricValues(objectMapper.createObjectNode())
                .build();
    }

    @Test
    @DisplayName("Success result: frame entry carries {value, details} per output field; failed = false")
    void success_frameEntryShape() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName("exact_match")
                .output(Map.of(
                        "score",
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.ONE)
                                .details(Map.of("reason", "matched"))
                                .build()))
                .build();
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", new TsmdEvaluationResult.Success(response, List.of("score"), 42L));
        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), tsmdResults, false));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> accuracy = (Map<String, Object>) result.frameEntry().get("Accuracy");
        @SuppressWarnings("unchecked")
        Map<String, Object> score = (Map<String, Object>) accuracy.get("score");
        assertThat(score.get("value")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) score.get("details");
        assertThat(details).containsEntry("reason", "matched");
    }

    @Test
    @DisplayName("Success with a nested-null details entry: the null survives into the frame entry instead of"
            + " being silently dropped by the shared NON_NULL-configured ObjectMapper")
    void success_detailsWithNestedNull_survivesInFrameEntry() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        // Mirror the production shared bean's NON_NULL property+content inclusion (JsonMapperConfiguration)
        // rather than the plain test-default mapper, so this test actually exercises the null-dropping bug
        // the fix addresses instead of trivially passing against a mapper that never drops nulls anyway.
        ObjectMapper nonNullObjectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        InlineMetricEvaluatorImpl evaluator = new InlineMetricEvaluatorImpl(
                context, metricRowEvaluator, evalSummaryBatchWriteClient, nonNullObjectMapper, Map.of(), executor);
        TestCaseRunResult row = row();

        Map<String, Object> details = new HashMap<>();
        details.put("reason", "matched");
        details.put("confidence", null);

        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName("exact_match")
                .output(Map.of(
                        "score",
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.ONE)
                                .details(details)
                                .build()))
                .build();
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", new TsmdEvaluationResult.Success(response, List.of("score"), 42L));
        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), tsmdResults, false));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> accuracy = (Map<String, Object>) result.frameEntry().get("Accuracy");
        @SuppressWarnings("unchecked")
        Map<String, Object> score = (Map<String, Object>) accuracy.get("score");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultDetails = (Map<String, Object>) score.get("details");
        assertThat(resultDetails).containsEntry("reason", "matched");
        assertThat(resultDetails).containsKey("confidence");
        assertThat(resultDetails.get("confidence")).isNull();
    }

    @Test
    @DisplayName("Success with a metric-level error output field: frame entry carries {error} for that field")
    void success_errorOutputField_frameEntryCarriesError() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        EvaluationResponseDto response = EvaluationResponseDto.builder()
                .metricName("exact_match")
                .output(Map.of(
                        "score",
                        MetricErrorDto.builder()
                                .type("error")
                                .message("provider error")
                                .build()))
                .build();
        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", new TsmdEvaluationResult.Success(response, List.of("score"), 10L));
        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), tsmdResults, true));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> accuracy = (Map<String, Object>) result.frameEntry().get("Accuracy");
        @SuppressWarnings("unchecked")
        Map<String, Object> score = (Map<String, Object>) accuracy.get("score");
        assertThat(score.get("error")).isEqualTo("provider error");
    }

    @Test
    @DisplayName("Failure (transport failure/timeout): wholesale {error} frame entry; failed = true")
    void failure_wholesaleErrorFrameEntry() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        Map<String, TsmdEvaluationResult> tsmdResults = Map.of(
                "Accuracy",
                new TsmdEvaluationResult.Failure(new RuntimeException("transport failure"), List.of("score"), 5L));
        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), tsmdResults, true));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> accuracy = (Map<String, Object>) result.frameEntry().get("Accuracy");
        assertThat(accuracy).containsEntry("error", "transport failure");
    }

    @Test
    @DisplayName("ConditionError: wholesale {error} frame entry; failed = true even though hasError is false"
            + " (design.md Decision 6 — a broken condition never fails the row's own EvalSummary, but"
            + " it must still abort the chain under inline evaluation)")
    void conditionError_failsInlineResultDespiteHasErrorFalse() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        Map<String, TsmdEvaluationResult> tsmdResults =
                Map.of("Accuracy", new TsmdEvaluationResult.ConditionError("broken condition", List.of("score")));
        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), tsmdResults, false));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> accuracy = (Map<String, Object>) result.frameEntry().get("Accuracy");
        assertThat(accuracy).containsEntry("error", "broken condition");
    }

    @Test
    @DisplayName("A clean condition=false contributes no tsmdResults entry: failed = false, empty frame entry")
    void cleanConditionFalse_noEntry_noFailure() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), false));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isFalse();
        assertThat(result.frameEntry()).isEmpty();
    }

    @Test
    @DisplayName("evaluate() never throws: an exception from MetricRowEvaluator folds into a failed result")
    void evaluatorException_foldsIntoFailedResult_doesNotThrow() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isTrue();
        assertThat(result.frameEntry()).isEmpty();
    }

    @Test
    @DisplayName("evaluateAndBuild throws: a fallback FAILED item is buffered so the row still gets exactly one"
            + " eval summary instead of none (Phase 2 skips SUCCESS rows in inline mode)")
    void evaluatorException_buffersFallbackFailedItemSoRowStillGetsOneSummary() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));
        when(metricRowEvaluator.buildFailedItem(eq(row), eq(context), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), true));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));
        evaluator.flush();

        assertThat(result.failed()).isTrue();
        verify(evalSummaryBatchWriteClient, times(1)).batchWrite(any(), any(), any(), any(), argThatHasSize(1));
    }

    @Test
    @DisplayName("An interruption cause is folded into a failed result and the interrupt flag is re-set")
    void interruptedCause_reSetsInterruptFlag() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        TestCaseRunResult row = row();

        when(metricRowEvaluator.evaluateAndBuild(eq(row), eq(context), any(), any(), any()))
                .thenThrow(new RuntimeException("wrapped", new InterruptedException("interrupted")));

        InlineMetricResult result = evaluator.evaluate(new InlineMetricRequest(row, Map.of()));

        assertThat(result.failed()).isTrue();
        assertThat(Thread.interrupted()).as("interrupt flag must be re-set").isTrue();
    }

    @Test
    @DisplayName("Buffer flushes automatically once it reaches the configured batch size")
    void bufferFlushesAtBatchSizeThreshold() {
        MetricEvaluationContext context = context(2, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        when(metricRowEvaluator.evaluateAndBuild(any(), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), false));

        evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
        verify(evalSummaryBatchWriteClient, never()).batchWrite(any(), any(), any(), any(), any());

        evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
        verify(evalSummaryBatchWriteClient, times(1)).batchWrite(any(), any(), any(), any(), argThatHasSize(2));
    }

    @Test
    @DisplayName("flush() writes remaining buffered items even below the batch-size threshold")
    void flushWritesRemainingBelowThreshold() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        when(metricRowEvaluator.evaluateAndBuild(any(), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), false));

        evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
        verify(evalSummaryBatchWriteClient, never()).batchWrite(any(), any(), any(), any(), any());

        evaluator.flush();
        verify(evalSummaryBatchWriteClient, times(1)).batchWrite(any(), any(), any(), any(), argThatHasSize(1));

        // A second flush() with nothing buffered is a no-op.
        evaluator.flush();
        verify(evalSummaryBatchWriteClient, times(1)).batchWrite(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("close() flushes any still-buffered items and shuts down the executor")
    void closeFlushesRemainingItems() {
        MetricEvaluationContext context = context(100, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        when(metricRowEvaluator.evaluateAndBuild(any(), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), false));

        evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
        evaluator.close();

        verify(evalSummaryBatchWriteClient, times(1)).batchWrite(any(), any(), any(), any(), argThatHasSize(1));
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("A batch-write failure sets the cancellation signal")
    void batchWriteFailure_setsCancellationSignal() {
        AtomicBoolean cancellationSignal = new AtomicBoolean(false);
        MetricEvaluationContext context = context(100, cancellationSignal);
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        when(metricRowEvaluator.evaluateAndBuild(any(), eq(context), any(), any(), any()))
                .thenReturn(new MetricRowEvaluationResult(item(), Map.of(), false));
        doThrow(new RuntimeException("db down"))
                .when(evalSummaryBatchWriteClient)
                .batchWrite(any(), any(), any(), any(), any());

        evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
        evaluator.flush();

        assertThat(cancellationSignal.get()).isTrue();
    }

    @Test
    @DisplayName("Concurrent evaluate() calls from N threads lose no summaries")
    void concurrentEvaluate_losesNoSummaries() throws InterruptedException {
        int threadCount = 50;
        MetricEvaluationContext context = context(1_000_000, new AtomicBoolean(false));
        InlineMetricEvaluatorImpl evaluator = newEvaluator(context);
        AtomicInteger callCount = new AtomicInteger();
        when(metricRowEvaluator.evaluateAndBuild(any(), eq(context), any(), any(), any()))
                .thenAnswer(inv -> {
                    callCount.incrementAndGet();
                    return new MetricRowEvaluationResult(item(), Map.of(), false);
                });

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < threadCount; i++) {
                callers.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    evaluator.evaluate(new InlineMetricRequest(row(), Map.of()));
                });
            }
            ready.await();
            go.countDown();
            callers.shutdown();
            assertThat(callers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            callers.shutdownNow();
        }

        evaluator.flush();

        assertThat(callCount.get()).isEqualTo(threadCount);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalSummaryBatchWriteItemDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(evalSummaryBatchWriteClient).batchWrite(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).hasSize(threadCount);
    }

    private static List<EvalSummaryBatchWriteItemDto> argThatHasSize(int size) {
        return argThat(list -> list != null && list.size() == size);
    }
}
