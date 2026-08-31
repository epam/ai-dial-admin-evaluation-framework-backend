package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.dto.MetricErrorDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.runner.job.InlineMetricEvaluator;
import com.epam.aidial.evaluation.runner.job.InlineMetricRequest;
import com.epam.aidial.evaluation.runner.job.InlineMetricResult;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-run {@link InlineMetricEvaluator}: scores a just-built SUCCESS row's TSMDs immediately (Phase 1,
 * inside {@code TurnLoopExecutor}'s total seam — see the {@code inline-metric-evaluation} change's
 * {@code design.md} Decision 3/5), buffering the resulting {@link EvalSummaryBatchWriteItemDto}s and
 * writing them to the analytics DB the same way {@code InProcessMetricEvaluationExecutor} does for
 * Phase 2.
 *
 * <p>One instance per run — created by {@link InlineMetricEvaluatorFactory}, not itself a Spring bean
 * (mirrors {@link PostgresResultBatchWriter}/{@link PostgresResultBatchWriterFactory}). {@code
 * evaluate()} MUST NOT throw (the {@link InlineMetricEvaluator} SPI's total-seam contract): every
 * internal failure is caught and folded into a failed {@link InlineMetricResult} instead of being
 * allowed to propagate into {@code TurnLoopExecutor}'s row-synthesis {@code catch} block.
 *
 * <p><b>Thread-safety.</b> {@code TestCaseRunner} dispatches up to {@code concurrencyLevel} test cases
 * concurrently on virtual threads, so {@link #evaluate} is called concurrently once {@code
 * concurrencyLevel > 1}. The buffer is guarded by a {@link ReentrantLock} around add/drain, with the
 * actual flush I/O performed outside the lock after a copy — the same discipline {@link
 * PostgresResultBatchWriter} uses for its own row buffer.
 */
@Slf4j
class InlineMetricEvaluatorImpl implements InlineMetricEvaluator {

    private final MetricEvaluationContext context;
    private final MetricRowEvaluator metricRowEvaluator;
    private final EvalSummaryBatchWriteClient evalSummaryBatchWriteClient;
    private final ObjectMapper objectMapper;

    /**
     * Same configuration as {@link #objectMapper} except default property AND content inclusion is
     * {@code ALWAYS} rather than the shared bean's {@code NON_NULL} — used only for converting a
     * {@code details} {@code Map<String,Object>} (which may legitimately carry nested Java {@code null}
     * values) into a {@link JsonNode} without silently dropping those null-valued entries (AGENTS.md:
     * "Don't serialize Map&lt;String,Object&gt; with Java null values using the shared ObjectMapper").
     * Built once via {@link ObjectMapper#rebuild()} rather than per-call.
     */
    private final ObjectMapper nullPreservingObjectMapper;

    private final Map<String, Semaphore> providerSemaphores;
    private final ExecutorService executor;

    private final List<EvalSummaryBatchWriteItemDto> buffer = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    InlineMetricEvaluatorImpl(
            MetricEvaluationContext context,
            MetricRowEvaluator metricRowEvaluator,
            EvalSummaryBatchWriteClient evalSummaryBatchWriteClient,
            ObjectMapper objectMapper,
            Map<String, Semaphore> providerSemaphores,
            ExecutorService executor) {
        this.context = context;
        this.metricRowEvaluator = metricRowEvaluator;
        this.evalSummaryBatchWriteClient = evalSummaryBatchWriteClient;
        this.objectMapper = objectMapper;
        this.nullPreservingObjectMapper = objectMapper
                .rebuild()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
                .build();
        this.providerSemaphores = providerSemaphores;
        this.executor = executor;
    }

    /**
     * Evaluates {@code request.row()}'s TSMDs, buffers the resulting EvalSummary item, and builds the
     * {@code $_metrics} frame entry for it. Never throws — see the class Javadoc's total-seam contract.
     *
     * <p>{@code failed} is {@code true} whenever the row's evaluation has {@code hasError} (a transport
     * failure, timeout, or a metric-level {@code type: "error"} output) OR any dispatched TSMD's
     * {@code condition} was broken (a {@code ConditionError}) — design.md Decision 6 requires a broken
     * condition to abort the chain under inline evaluation even though it does NOT flip the row's own
     * EvalSummary to FAILED (that {@code hasError} computation is intentionally unchanged from Phase 2,
     * where a broken condition never fails the row). A clean {@code condition = false} contributes no
     * entry to {@code tsmdResults} at all, so it never triggers this.
     */
    @Override
    public InlineMetricResult evaluate(InlineMetricRequest request) {
        TestCaseRunResult row = request.row();
        try {
            MetricRowEvaluationResult rowResult = metricRowEvaluator.evaluateAndBuild(
                    row, context, providerSemaphores, executor, request.accumulatedMetrics());
            addToBuffer(rowResult.item());
            Map<String, Object> frameEntry = buildFrameEntry(rowResult.tsmdResults());
            boolean anyConditionError = rowResult.tsmdResults().values().stream()
                    .anyMatch(TsmdEvaluationResult.ConditionError.class::isInstance);
            return new InlineMetricResult(frameEntry, rowResult.hasError() || anyConditionError);
        } catch (RuntimeException e) {
            log.warn("Inline metric evaluation threw for result {}: {}", row.getId(), e.getMessage(), e);
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // metricRowEvaluator.evaluateAndBuild threw before it could build an EvalSummary item, so —
            // unlike the normal path — nothing has been buffered for this row yet. Phase 2 skips SUCCESS
            // rows in inline mode, so without this fallback the row would end up with NO eval summary at
            // all. Buffer a wholesale-FAILED fallback item instead; this call is itself guarded (rather
            // than allowed to propagate) to preserve the total-seam contract even if it also fails.
            try {
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.toString();
                addToBuffer(metricRowEvaluator
                        .buildFailedItem(row, context, errorMessage)
                        .item());
            } catch (RuntimeException bufferException) {
                log.warn(
                        "Failed to buffer fallback eval summary for result {}: {}",
                        row.getId(),
                        bufferException.getMessage(),
                        bufferException);
            }
            return new InlineMetricResult(Map.of(), true);
        }
    }

    /**
     * Flushes any buffered EvalSummary items to the analytics DB. Called by {@code TestSuiteEvaluationJob}
     * immediately after {@code evaluationExecutor.execute(context)} returns, strictly before Phase 2/3
     * (design.md Decision 5's "Flush timing"). A no-op when the buffer is empty.
     */
    void flush() {
        List<EvalSummaryBatchWriteItemDto> toFlush;
        lock.lock();
        try {
            if (buffer.isEmpty()) {
                return;
            }
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        } finally {
            lock.unlock();
        }
        doFlush(toFlush);
    }

    /**
     * Safety-net cleanup: flushes any still-buffered items (a no-op if {@link #flush()} already ran)
     * and shuts down the run's dedicated executor. Called from {@code TestSuiteEvaluationJob}'s existing
     * {@code finally} block.
     */
    void close() {
        flush();
        executor.shutdownNow();
    }

    private void addToBuffer(EvalSummaryBatchWriteItemDto item) {
        List<EvalSummaryBatchWriteItemDto> toFlush = null;
        lock.lock();
        try {
            buffer.add(item);
            if (buffer.size() >= context.getBatchSize()) {
                toFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        } finally {
            lock.unlock();
        }
        if (toFlush != null) {
            doFlush(toFlush);
        }
    }

    private void doFlush(List<EvalSummaryBatchWriteItemDto> batch) {
        try {
            evalSummaryBatchWriteClient.batchWrite(
                    context.getTestSuiteId(),
                    context.getTestSuiteRunId(),
                    context.getComputationId(),
                    context.getComputedAtMs(),
                    batch);
            log.debug("Flushed {} inline eval summaries for run {}", batch.size(), context.getTestSuiteRunId());
        } catch (RuntimeException e) {
            log.error(
                    "Inline eval summary batch write failed for run {}, setting cancellation signal: {}",
                    context.getTestSuiteRunId(),
                    e.getMessage(),
                    e);
            context.getCancellationSignal().set(true);
        }
    }

    /**
     * Builds the {@code $_metrics} frame entry for one row (design.md Decision 2):
     * {@code {tsmdName: {field: {value, details}}}} for a successful TSMD (per-field {@code {"error":
     * msg}} for a metric-level error output), or {@code {tsmdName: {"error": msg}}} (wholesale) for a
     * transport {@code Failure} or a {@code ConditionError}. Built with {@code ObjectNode}/{@code
     * putNull} and re-read via {@code objectMapper.readValue(json, Object.class)} so explicit nulls
     * survive — never handed a {@code Map} containing Java {@code null}s to the shared {@code
     * NON_NULL}-configured {@code ObjectMapper} directly. A {@code details} map's own nested nulls are
     * additionally protected at the source by {@link #nullPreservingObjectMapper} (see
     * {@link #buildFieldNode}) before they ever reach this method's tree.
     */
    private Map<String, Object> buildFrameEntry(Map<String, TsmdEvaluationResult> tsmdResults) {
        if (tsmdResults.isEmpty()) {
            return Map.of();
        }
        ObjectNode root = objectMapper.createObjectNode();
        for (Map.Entry<String, TsmdEvaluationResult> entry : tsmdResults.entrySet()) {
            root.set(entry.getKey(), buildTsmdNode(entry.getValue()));
        }
        try {
            String json = objectMapper.writeValueAsString(root);
            Object parsed = objectMapper.readValue(json, Object.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) parsed;
            return result;
        } catch (JacksonException e) {
            log.warn("Failed to build $_metrics frame entry, omitting it: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    private ObjectNode buildTsmdNode(TsmdEvaluationResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (result) {
            case TsmdEvaluationResult.Success success -> {
                Map<String, MetricOutputDto> output = success.response().getOutput();
                if (output != null) {
                    for (Map.Entry<String, MetricOutputDto> fieldEntry : output.entrySet()) {
                        node.set(fieldEntry.getKey(), buildFieldNode(fieldEntry.getValue()));
                    }
                }
            }
            case TsmdEvaluationResult.Failure failure ->
                node.put("error", failure.error().getMessage());
            case TsmdEvaluationResult.ConditionError conditionError -> node.put("error", conditionError.message());
        }
        return node;
    }

    private ObjectNode buildFieldNode(MetricOutputDto field) {
        ObjectNode fieldNode = objectMapper.createObjectNode();
        if (field instanceof MetricOutputFieldDto valueField) {
            if (valueField.getValue() == null) {
                fieldNode.putNull("value");
            } else {
                fieldNode.put("value", valueField.getValue());
            }
            Map<String, Object> details = valueField.getDetails();
            if (details == null) {
                fieldNode.putNull("details");
            } else {
                fieldNode.set("details", nullPreservingObjectMapper.convertValue(details, JsonNode.class));
            }
        } else if (field instanceof MetricErrorDto error) {
            fieldNode.put("error", error.getMessage());
        }
        return fieldNode;
    }
}
