package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.constants.JsonataReservedNames;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.runner.service.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.service.SerializedBody;
import com.epam.aidial.evaluation.runner.util.QuietJsonService;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unified turn-loop executor for <b>one request</b> of a suite's request chain (single request or one
 * request within a multi-request chain — see {@link RequestChainExecutor}), covering every DEPLOYMENT HTTP
 * test case, single-turn and multi-turn alike. Turn count {@code N} is derived, not fixed:
 *
 * <ul>
 *   <li>a single-turn case ({@code multiTurnData} absent) always runs {@code N = 1};</li>
 *   <li>a multi-turn case whose effective input bindings reference at least one {@code perTurn = true}
 *       schema field ({@link PerTurnBindingDetector}) runs {@code N = multiTurnData.length}, one turn per
 *       array element, with the merged shared+per-turn view driving each turn;</li>
 *   <li>a multi-turn case with no per-turn binding collapses to {@code N = 1}, built from the case's shared
 *       {@code data} only — it does not resend the same request {@code multiTurnData.length} times.</li>
 * </ul>
 *
 * <p>For the {@code N = 1} case (single-turn or the no-per-turn-binding collapse), the persisted row's
 * {@code turnIndex}/{@code totalTurns} are left at their builder defaults (0/1) — i.e. indistinguishable
 * from a genuine single-turn row, exactly as today. Only the {@code N > 1} per-turn-binding path stamps
 * explicit {@code turnIndex}/{@code totalTurns}.
 *
 * <p>Each turn's request body is evaluated as JSONata ({@link RequestResolver#resolveForRun}) with a
 * {@code Frame} carrying the accumulated response columns extracted so far — this request's own prior
 * turns, seeded from {@link #execute}'s {@code initialFrame} parameter (every column extracted by earlier
 * requests in the chain; empty for request #0). Turn 0 of request #0 in a single-request chain therefore
 * evaluates with an empty frame exactly as before this class was generalized for chains. There is no
 * hardcoded {@code messages} array or {@code choices[0].message} reply path: history accumulation
 * across turns is entirely the author's JSONata expression (typically {@code $append($history, [...])})
 * over whatever the suite's own response columns extract. Turns stream like single-turn requests; the
 * assembled response body (including DIAL {@code custom_content}) is what response columns are extracted
 * from ({@link DeploymentTurnInvoker}).
 *
 * <p>The loop is fail-fast: a turn that fails (non-2xx after retries, timeout/network error, oversized
 * response, or a request body that does not JSONata-evaluate to a JSON object) aborts the run. Earlier
 * turns persist as {@code SUCCESS} rows; the failing turn persists as one {@code ERROR}/{@code FAILED} row;
 * later turns are never sent. The whole test-case run runs inside the caller's single worker task /
 * semaphore permit; turns are sequential, sharing the test-case-run span's {@code traceId}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TurnLoopExecutor {

    private final RequestResolver requestResolver;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final RunnerJsonbMapper jsonbMapper;
    private final QuietJsonService jsonService;
    private final DeploymentTurnInvoker deploymentTurnInvoker;
    private final PerTurnBindingDetector perTurnBindingDetector;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Runs this request's turn loop, returning one {@link TestCaseRunResult} per executed turn (fewer than
     * planned {@code N} on early abort; exactly one for the {@code N = 1} case), the accumulated frame this
     * request ended with, the accumulated {@code $_metrics} frame entries this request ended with, and
     * whether this request aborted (Decision 8/9/11 of the {@code add-multi-request-suite} change's
     * {@code design.md}; Decision 2/3 of the {@code inline-metric-evaluation} change's {@code design.md}).
     * {@code spec} carries this request's own template/bindings/columns/endpoint; {@code initialFrame} is
     * the frame accumulated by earlier requests in the chain (empty for request #0); {@code
     * initialMetrics} is the {@code $_metrics} frame accumulated by earlier requests in the chain (empty
     * for request #0) — bound into the per-turn request-body resolution frame as {@code $_metrics} only
     * when {@code context.getInlineMetricEvaluator() != null}; a non-inline run's frame carries no {@code
     * _metrics} key at all, not an empty map. {@code traceId} is the test-case-run span's id (shared by
     * every row of every request).
     */
    public RequestExecutionResult execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            RequestExecutionSpec spec,
            Map<String, Object> initialFrame,
            Map<String, Object> initialMetrics,
            String traceId,
            long execStartedAtMs) {

        final List<InputBindingDto> bindings = input.getInputBindingsOverride() != null
                ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                : spec.inputBindings();
        final RequestTemplateDto template = input.getRequestTemplateOverride() != null
                ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                : spec.requestTemplate();
        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final HttpMethod method =
                spec.endpointRef() != null ? spec.endpointRef().getMethod() : null;
        final List<ResponseColumnDefinitionDto> responseColumns = spec.responseColumns();
        // Requests are stamped only when the chain has more than one request (Decision 9) — a
        // single-request chain leaves requestIndex/totalRequests at their builder defaults (0/1), so its
        // rows are byte-identical to rows written before this capability existed.
        final boolean stampRequestIndices = spec.totalRequests() > 1;
        final Integer persistedRequestIndex = stampRequestIndices ? spec.requestIndex() : null;
        final Integer persistedTotalRequests = stampRequestIndices ? spec.totalRequests() : null;

        final TurnPlan plan = buildTurnPlan(input, context, bindings);
        if (plan == null) {
            log.warn("Multi-turn test case {} has no readable turns", input.getTestCaseId());
            final Map<String, Object> accumulated = new LinkedHashMap<>(initialFrame);
            final Map<String, Object> accumulatedMetrics = new LinkedHashMap<>(initialMetrics);
            final RowIdentity emptyTurnsIdentity =
                    new RowIdentity(null, null, persistedRequestIndex, persistedTotalRequests);
            final TestCaseRunResult row = buildEmptyTurnsErrorRow(
                    input, context, runIndex, traceId, execStartedAtMs, emptyTurnsIdentity, accumulated);
            return new RequestExecutionResult(List.of(row), accumulated, accumulatedMetrics, true);
        }

        final List<Map<String, Object>> turnDataList = plan.turnDataList();
        final int totalTurns = turnDataList.size();
        final List<TestCaseRunResult> results = new ArrayList<>();
        // Seeded from the chain's accumulated frame (empty for request #0): turn 0's resolution frame is
        // exactly this map, and each successful turn folds its own extraction in (Decision 4). Within one
        // request this reduces to the pre-existing "previous turn's extraction" behavior, because every
        // turn re-extracts the full column set defined for this request.
        Map<String, Object> accumulated = new LinkedHashMap<>(initialFrame);
        // Threaded the same way as accumulated, but never persisted as extracted_columns — see
        // buildRequestFrame. Folding a turn's own metric-evaluation output in is added by the total-seam
        // wiring (inline-metric-evaluation change's design.md Decision 3); this method only threads and
        // binds it for now.
        Map<String, Object> accumulatedMetrics = new LinkedHashMap<>(initialMetrics);
        boolean aborted = false;
        int turnIndex = 0;
        try {
            for (turnIndex = 0; turnIndex < totalTurns; turnIndex++) {
                final Map<String, Object> turnData = turnDataList.get(turnIndex);
                final RowIdentity rowIdentity = new RowIdentity(
                        plan.stampTurnIndices() ? turnIndex : null,
                        plan.stampTurnIndices() ? totalTurns : null,
                        persistedRequestIndex,
                        persistedTotalRequests);
                final String persistedDataJson =
                        plan.stampTurnIndices() ? jsonService.writeOrToString(turnData) : plan.verbatimDataJson();

                final long turnStart = clock.millis();
                final TurnStepResult step = runOneTurn(
                        template,
                        bindings,
                        turnData,
                        deploymentId,
                        method,
                        responseColumns,
                        buildRequestFrame(accumulated, accumulatedMetrics, context),
                        input.getTestCaseId(),
                        turnIndex,
                        context);
                final long turnEnd = clock.millis();

                if (step.control() == TurnControl.CONTINUE) {
                    accumulated = mergeAccumulated(accumulated, step.extractedValues());
                    // The row is built first (id minted, all columns/identity populated) so the seam below
                    // evaluates against the real, final row — never a partial one (design.md Decision 3).
                    final TestCaseRunResult row = buildTurnRow(
                            input,
                            context,
                            runIndex,
                            traceId,
                            turnStart,
                            turnEnd,
                            rowIdentity,
                            ExecutionStatus.SUCCESS,
                            step.outcome(),
                            step.requestBodyJson(),
                            serializeAccumulated(accumulated),
                            step.extractionWarningsJson(),
                            persistedDataJson);
                    boolean metricsFailed = false;
                    if (context.getInlineMetricEvaluator() != null) {
                        final InlineMetricResult metricResult = evaluateInlineMetrics(row, accumulatedMetrics, context);
                        accumulatedMetrics = mergeAccumulated(accumulatedMetrics, metricResult.frameEntry());
                        metricsFailed = metricResult.failed();
                    }
                    // The row is appended unconditionally — a metric failure never replaces or drops the
                    // real SUCCESS row (Decision 6); it only stops later turns/requests from running.
                    results.add(row);
                    if (metricsFailed) {
                        aborted = true;
                        break;
                    }
                } else {
                    final boolean requestIssued = step.outcome() != null;
                    if (requestIssued || !context.getCancellationSignal().get()) {
                        final ResponseColumnExtractor.ExtractionResult abortExtraction = step.outcome() != null
                                ? responseColumnExtractor.extract(
                                        responseColumns, step.outcome().responseBody(), step.requestBodyJson())
                                : new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of());
                        final Map<String, Object> rowAccumulated =
                                mergeAccumulated(accumulated, abortExtraction.values());
                        results.add(buildTurnRow(
                                input,
                                context,
                                runIndex,
                                traceId,
                                turnStart,
                                turnEnd,
                                rowIdentity,
                                step.status(),
                                step.outcome(),
                                step.requestBodyJson(),
                                serializeAccumulated(rowAccumulated),
                                abortExtraction.extractionWarnings(),
                                persistedDataJson));
                    }
                    aborted = true;
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Turn loop failed for test case {} in suite {} at turn {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    turnIndex,
                    e.getMessage(),
                    e);
            final int safeIndex = Math.min(turnIndex, totalTurns - 1);
            final long now = clock.millis();
            final TurnOutcome outcome = buildResolutionErrorOutcome(e);
            final ResponseColumnExtractor.ExtractionResult extraction =
                    responseColumnExtractor.extract(responseColumns, outcome.responseBody());
            final Map<String, Object> rowAccumulated = mergeAccumulated(accumulated, extraction.values());
            final RowIdentity rowIdentity = new RowIdentity(
                    plan.stampTurnIndices() ? safeIndex : null,
                    plan.stampTurnIndices() ? totalTurns : null,
                    persistedRequestIndex,
                    persistedTotalRequests);
            results.add(buildTurnRow(
                    input,
                    context,
                    runIndex,
                    traceId,
                    now,
                    now,
                    rowIdentity,
                    ExecutionStatus.ERROR,
                    outcome,
                    null,
                    serializeAccumulated(rowAccumulated),
                    extraction.extractionWarnings(),
                    plan.stampTurnIndices()
                            ? jsonService.writeOrToString(turnDataList.get(safeIndex))
                            : plan.verbatimDataJson()));
            aborted = true;
        }
        return new RequestExecutionResult(results, accumulated, accumulatedMetrics, aborted);
    }

    /**
     * Returns the frame to bind for one turn's request-body resolution: {@code accumulated} (the
     * response-column accumulator) unchanged when this run is non-inline — so {@code $exists($_metrics)}
     * evaluates to {@code false} and this method is a byte-identical no-op for a suite that never
     * references {@code $_metrics} — or a copy with an additional {@code $_metrics} entry when {@code
     * context.getInlineMetricEvaluator() != null} (inline-metric-evaluation change's design.md Decision
     * 2/3). {@code accumulated} itself is never mutated here — it is also what gets persisted as {@code
     * extracted_columns}, which {@code $_metrics} must never become part of.
     */
    private Map<String, Object> buildRequestFrame(
            Map<String, Object> accumulated, Map<String, Object> accumulatedMetrics, EvaluationContext context) {
        if (context.getInlineMetricEvaluator() == null) {
            return accumulated;
        }
        final Map<String, Object> frame = new LinkedHashMap<>(accumulated);
        frame.put(JsonataReservedNames.METRICS_FRAME_BINDING, accumulatedMetrics);
        return frame;
    }

    /**
     * Invokes {@code context.getInlineMetricEvaluator()} against {@code row}, guaranteeing the seam is
     * <b>total</b> (design.md Decision 3): the SPI contract already states {@code evaluate()} MUST NOT
     * throw, but this call site is the last line of defense against a contract violation — any {@link
     * RuntimeException} escaping the evaluator is caught here and folded into a failed {@link
     * InlineMetricResult} instead of being allowed to reach the outer {@code catch (RuntimeException)} that
     * would otherwise synthesize a {@code REQUEST_RESOLUTION_ERROR} row in place of the real SUCCESS row.
     * A caught exception whose cause is an {@link InterruptedException} re-sets the thread's interrupt flag
     * before returning, so the interruption is not silently swallowed.
     */
    private InlineMetricResult evaluateInlineMetrics(
            TestCaseRunResult row, Map<String, Object> accumulatedMetrics, EvaluationContext context) {
        try {
            return context.getInlineMetricEvaluator().evaluate(new InlineMetricRequest(row, accumulatedMetrics));
        } catch (RuntimeException e) {
            log.warn(
                    "Inline metric evaluator threw for test case {} row {}: {}",
                    row.getTestCaseId(),
                    row.getId(),
                    e.getMessage(),
                    e);
            if (e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new InlineMetricResult(Map.of(), true);
        }
    }

    /**
     * Folds {@code turnValues} into {@code base}, later keys overwriting earlier ones. Returns {@code base}
     * unchanged (no copy) when {@code turnValues} is empty — the common case for an abort that never issued
     * a request. Names never collide across requests (suite-wide response-column uniqueness is enforced at
     * write time), so this is purely the within-request "later turn wins" rule (Decision 4). Reused for the
     * {@code $_metrics} accumulator's own last-writer-wins-per-{@code tsmdName} fold (inline-metric-evaluation
     * change's design.md Decision 2/3) — the merge semantics are identical, only the map's contents differ.
     */
    private Map<String, Object> mergeAccumulated(Map<String, Object> base, Map<String, Object> turnValues) {
        if (turnValues.isEmpty()) {
            return base;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(turnValues);
        return merged;
    }

    /**
     * Serializes the accumulated column map for persistence as {@code extracted_columns}, preserving
     * explicit JSON nulls via {@code ObjectNode.putNull} — the shared {@code ObjectMapper}'s
     * NON_NULL-inclusion default silently drops null-valued {@code Map} entries, so a plain {@code
     * objectMapper.writeValueAsString(map)} would corrupt a failed-extraction column into a missing key
     * instead of an explicit {@code null} (see {@link ResponseColumnExtractor}, which follows the same
     * tree-based pattern for the identical reason). For a single-request chain {@code base} is always
     * empty, so this reduces to serializing exactly this turn's own extraction — byte-identical to the
     * pre-generalization output.
     */
    private String serializeAccumulated(Map<String, Object> accumulated) {
        final ObjectNode node = jsonService.createObjectNode();
        for (Map.Entry<String, Object> entry : accumulated.entrySet()) {
            if (entry.getValue() == null) {
                node.putNull(entry.getKey());
            } else {
                node.set(entry.getKey(), objectMapper.convertValue(entry.getValue(), JsonNode.class));
            }
        }
        return jsonService.writeOrToString(node);
    }

    /**
     * Synthesizes the {@code REQUEST_RESOLUTION_ERROR} envelope for a per-turn request-building failure
     * that escapes {@link #runOneTurn} (e.g. an invalid {@code FILE} ref via {@code DialFileRefResolver},
     * an unsupported content type from {@link RequestBodySerializerRegistry}, or a URL-build failure) —
     * mirroring the pre-unification {@code EvaluationWorker}'s single-turn resolution-error path so the
     * persisted row still carries a diagnosable {@code responseBody} envelope and reconciled extraction
     * warnings instead of {@code null}/{@code null}.
     */
    private TurnOutcome buildResolutionErrorOutcome(RuntimeException e) {
        final String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                ExecutionErrorCodes.REQUEST_RESOLUTION_ERROR, e.getMessage(), objectMapper);
        final String logDetails = buildErrorLogDetails("Request resolution failed: " + e.getMessage());
        return new TurnOutcome(ExecutionStatus.ERROR, null, errorBody, 0, logDetails);
    }

    /**
     * Runs one turn without mutating shared state: evaluates the request body as JSONata against
     * {@code frameBindings}, sends it (streaming), and on success extracts response columns.
     */
    private TurnStepResult runOneTurn(
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            Map<String, Object> turnData,
            String deploymentId,
            HttpMethod method,
            List<ResponseColumnDefinitionDto> responseColumns,
            Map<String, Object> frameBindings,
            UUID testCaseId,
            int turnIndex,
            EvaluationContext context) {

        if (context.getCancellationSignal().get()) {
            return TurnStepResult.abortBeforeRequest(ExecutionStatus.ERROR, null, null);
        }

        final ResolvedRequestDto resolved;
        try {
            resolved = requestResolver.resolveForRun(template, bindings, turnData, frameBindings);
        } catch (RequestBodyEvaluationException e) {
            log.warn(
                    "Turn {} for test case {} failed request-body evaluation: {}",
                    turnIndex,
                    testCaseId,
                    e.getMessage(),
                    e);
            return TurnStepResult.abortBeforeRequest(ExecutionStatus.ERROR, null, buildBodyEvaluationErrorOutcome(e));
        }

        final ResolvedBodyDto resolvedBody = resolved.getBody();
        final String requestBodyJson = serializeResolvedBodyForAnalytics(resolvedBody);

        final String path = urlBuilder.buildUrl(deploymentId, resolved.getUrl());
        final HttpHeaders headers = buildHeaders(resolved.getHeaders());
        final MultiValueMap<String, String> queryParams =
                DeploymentInvocationSupport.buildQueryParams(resolved.getQueryParams());
        final SerializedBody serialized = serializerRegistry.serialize(resolvedBody);
        if (serialized != null && !MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
            headers.setContentType(serialized.contentType());
        }

        final TurnOutcome outcome = deploymentTurnInvoker.invoke(
                context, method, path, headers, queryParams, serialized != null ? serialized.body() : null);
        if (outcome.status() != ExecutionStatus.SUCCESS) {
            return TurnStepResult.abortAfterRequest(outcome.status(), requestBodyJson, outcome);
        }

        final ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(responseColumns, outcome.responseBody(), requestBodyJson);
        return TurnStepResult.completed(
                requestBodyJson,
                outcome,
                extraction.extractedColumns(),
                extraction.extractionWarnings(),
                extraction.values());
    }

    private TurnOutcome buildBodyEvaluationErrorOutcome(RequestBodyEvaluationException e) {
        final String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                ExecutionErrorCodes.REQUEST_BODY_EVALUATION_ERROR, e.getMessage(), objectMapper);
        final String logDetails = buildErrorLogDetails("Request body evaluation failed: " + e.getMessage());
        return new TurnOutcome(ExecutionStatus.ERROR, null, errorBody, 0, logDetails);
    }

    /**
     * Serializes the resolved (JSONata-evaluated) body for analytics storage: for a JSON body, the content
     * map itself (no {@code contentType} wrapper), matching the single-turn convention.
     */
    private String serializeResolvedBodyForAnalytics(ResolvedBodyDto body) {
        if (body == null) {
            return null;
        }
        final Object toSerialize = body instanceof ResolvedJsonBodyDto jsonBody ? jsonBody.getContent() : body;
        return jsonService.writeOrToString(toSerialize);
    }

    private TestCaseRunResult buildTurnRow(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long turnStart,
            long turnEnd,
            RowIdentity identity,
            ExecutionStatus status,
            TurnOutcome outcome,
            String requestBodyJson,
            String extractedColumnsJson,
            String extractionWarningsJson,
            String testCaseDataJson) {
        TestCaseRunResult.TestCaseRunResultBuilder builder = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(testCaseDataJson)
                .requestBody(requestBodyJson)
                .responseBody(outcome != null ? outcome.responseBody() : null)
                .responseStatusCode(outcome != null ? outcome.statusCode() : null)
                .executionStatus(status)
                .execStartedAtMs(turnStart)
                .execCompletedAtMs(turnEnd)
                .execDurationMs(turnEnd - turnStart)
                .traceId(traceId)
                .extractedColumns(extractedColumnsJson)
                .extractionWarnings(extractionWarningsJson)
                .retryCount(outcome != null ? outcome.retryCount() : 0)
                .logDetails(outcome != null ? outcome.logDetails() : null)
                .createdAtMs(context.getCreatedAtMs());
        stampIdentity(builder, identity);
        return builder.build();
    }

    /**
     * Stamps {@code identity}'s non-null fields onto {@code builder}. Leaving a field {@code null} keeps the
     * {@link TestCaseRunResult} builder default (0 for {@code turnIndex}/{@code requestIndex}, 1 for {@code
     * totalTurns}/{@code totalRequests}) — the {@code N = 1} case (single-turn or the no-per-turn-binding
     * collapse) of a single-request chain is byte-identical to today's single-turn row. Only the {@code N >
     * 1} per-turn-binding path stamps explicit turn values, and only a > 1-request chain stamps explicit
     * request values (Decision 9).
     */
    private void stampIdentity(TestCaseRunResult.TestCaseRunResultBuilder builder, RowIdentity identity) {
        if (identity.turnIndex() != null) {
            builder.turnIndex(identity.turnIndex());
        }
        if (identity.totalTurns() != null) {
            builder.totalTurns(identity.totalTurns());
        }
        if (identity.requestIndex() != null) {
            builder.requestIndex(identity.requestIndex());
        }
        if (identity.totalRequests() != null) {
            builder.totalRequests(identity.totalRequests());
        }
    }

    /**
     * The four nullable identity stamps threaded through a persisted row: {@code turnIndex}/{@code
     * totalTurns} (null unless the {@code N > 1} per-turn-binding path is active) and {@code requestIndex}/
     * {@code totalRequests} (null unless the chain has more than one request). See {@link #stampIdentity}.
     */
    private record RowIdentity(Integer turnIndex, Integer totalTurns, Integer requestIndex, Integer totalRequests) {}

    /** Parses the case's shared (test-case-level) data map; empty when absent. */
    private Map<String, Object> parseSharedData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return Map.of();
        }
        return jsonService.readMapOrEmpty(dataJson);
    }

    /**
     * Builds a turn's effective view: the case's shared data overlaid with that turn's own per-turn map.
     * Per-turn keys take precedence (scope placement makes overlap unreachable via the API; this is a
     * defensive tiebreak). Returns the turn map unchanged when there is no shared data.
     */
    private static Map<String, Object> mergeSharedAndTurn(Map<String, Object> shared, Map<String, Object> turn) {
        if (shared.isEmpty()) {
            return turn;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(shared);
        merged.putAll(turn);
        return merged;
    }

    /**
     * Parses the frozen {@code multi_turn_data} array into an ordered list of turn-data maps. Returns an
     * empty list for a null/blank/non-array payload (surfaced by the caller as a single ERROR row).
     */
    private List<Map<String, Object>> parseTurns(String multiTurnDataJson) {
        if (multiTurnDataJson == null || multiTurnDataJson.isBlank()) {
            return List.of();
        }
        final JsonNode root = jsonService.readTreeOrEmpty(multiTurnDataJson);
        if (!root.isArray()) {
            return List.of();
        }
        final List<Map<String, Object>> turns = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            turns.add(node == null || node.isNull() ? Map.of() : jsonService.readMapOrEmpty(node.toString()));
        }
        return turns;
    }

    /**
     * Determines the turn plan for this test case (Decision 4): a single-turn case always plans one turn
     * from its own data; a multi-turn case with no per-turn binding also collapses to one turn built from
     * the shared data only; a multi-turn case with a per-turn binding plans one turn per {@code
     * multiTurnData} element, merging shared + per-turn data. Returns {@code null} when {@code
     * multiTurnData} is present but has no readable turns (defensive — write-time validation rejects an
     * empty array).
     */
    private TurnPlan buildTurnPlan(TestCaseRunInput input, EvaluationContext context, List<InputBindingDto> bindings) {
        if (input.getMultiTurnData() == null) {
            final Map<String, Object> data = parseSharedData(input.getTestCaseData());
            return new TurnPlan(List.of(data), false, input.getTestCaseData());
        }

        final List<Map<String, Object>> turns = parseTurns(input.getMultiTurnData());
        if (turns.isEmpty()) {
            return null;
        }

        final Map<String, Object> sharedData = parseSharedData(input.getTestCaseData());
        final List<FieldDefinitionDto> schema = context.getSnapshotTestCaseSchema();
        final boolean perTurn = perTurnBindingDetector.referencesPerTurnField(bindings, schema);
        if (!perTurn) {
            return new TurnPlan(List.of(sharedData), false, input.getTestCaseData());
        }

        final List<Map<String, Object>> merged = new ArrayList<>(turns.size());
        for (Map<String, Object> turn : turns) {
            merged.add(mergeSharedAndTurn(sharedData, turn));
        }
        return new TurnPlan(merged, true, null);
    }

    /**
     * A resolved turn plan: the per-turn effective data views to execute, whether the loop stamps explicit
     * {@code turnIndex}/{@code totalTurns} (true only for the {@code N > 1} per-turn-binding path), and —
     * for the non-stamping {@code N = 1} path — the verbatim {@code testCaseData} JSON to persist unchanged
     * (matching single-turn's byte-identical persistence).
     */
    private record TurnPlan(
            List<Map<String, Object>> turnDataList, boolean stampTurnIndices, String verbatimDataJson) {}

    /**
     * Single degenerate ERROR row for a case whose {@code multi_turn_data} carries no readable turns
     * (defensive — validation rejects an empty array at write time). Emitted with {@code turn_index=0},
     * {@code total_turns=1} and the failure captured in {@code logDetails}.
     */
    private TestCaseRunResult buildEmptyTurnsErrorRow(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            RowIdentity identity,
            Map<String, Object> accumulated) {
        final long now = clock.millis();
        TestCaseRunResult.TestCaseRunResultBuilder builder = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(input.getTestCaseData())
                .requestBody(null)
                .responseBody(null)
                .responseStatusCode(null)
                .executionStatus(ExecutionStatus.ERROR)
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(now)
                .execDurationMs(now - execStartedAtMs)
                .traceId(traceId)
                // Serializing the (empty, for request #0) accumulated frame reduces to "{}", byte-identical
                // to the pre-generalization hardcoded value.
                .extractedColumns(serializeAccumulated(accumulated))
                .extractionWarnings("[]")
                .retryCount(0)
                .logDetails(buildErrorLogDetails("Multi-turn case has no readable turns"))
                .createdAtMs(context.getCreatedAtMs());
        stampIdentity(builder, identity);
        return builder.build();
    }

    private String buildErrorLogDetails(String message) {
        final var node = jsonService.createObjectNode();
        node.put("error", message);
        return jsonService.writeOrToString(node);
    }

    private HttpHeaders buildHeaders(List<KeyValueTemplateDto> resolvedHeaders) {
        final HttpHeaders headers = new HttpHeaders();
        final Set<String> blacklist = evaluationRunProperties.getExecution().getHeaderBlacklist().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (resolvedHeaders != null) {
            for (KeyValueTemplateDto kv : resolvedHeaders) {
                if (kv.getKey() != null
                        && kv.getValue() != null
                        && !blacklist.contains(kv.getKey().toLowerCase())) {
                    headers.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return headers;
    }

    private enum TurnControl {
        CONTINUE,
        ABORT
    }

    /**
     * Result of one turn, applied by the caller: {@code extractedValues} is the reconciled per-column
     * value map handed to the next turn's frame; {@code control} tells the loop whether to continue or
     * abort (with the terminal {@code status}). When {@code outcome} is non-null the turn issued its HTTP
     * request (or, for a body-evaluation failure, synthesized an error outcome without ever sending one).
     */
    private record TurnStepResult(
            TurnControl control,
            ExecutionStatus status,
            String requestBodyJson,
            TurnOutcome outcome,
            String extractedColumnsJson,
            String extractionWarningsJson,
            Map<String, Object> extractedValues) {

        static TurnStepResult abortBeforeRequest(ExecutionStatus status, String requestBodyJson, TurnOutcome outcome) {
            return new TurnStepResult(TurnControl.ABORT, status, requestBodyJson, outcome, null, null, Map.of());
        }

        static TurnStepResult abortAfterRequest(ExecutionStatus status, String requestBodyJson, TurnOutcome outcome) {
            return new TurnStepResult(TurnControl.ABORT, status, requestBodyJson, outcome, null, null, Map.of());
        }

        static TurnStepResult completed(
                String requestBodyJson,
                TurnOutcome outcome,
                String extractedColumnsJson,
                String extractionWarningsJson,
                Map<String, Object> extractedValues) {
            return new TurnStepResult(
                    TurnControl.CONTINUE,
                    ExecutionStatus.SUCCESS,
                    requestBodyJson,
                    outcome,
                    extractedColumnsJson,
                    extractionWarningsJson,
                    extractedValues);
        }
    }
}
