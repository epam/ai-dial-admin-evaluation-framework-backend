package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.service.domain.ResolvedRequestService;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
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

/**
 * Multi-turn executor for {@code DEPLOYMENT} suites. Drives a sequence of chat-completions turns for one
 * multi-turn test case, accumulating a running {@code messages} history and re-sending the full history
 * each turn. A multi-turn case is a <b>single test-case row</b> carrying an ordered array of turn-data maps
 * ({@code multi_turn_data}), frozen into the snapshot input at run-creation time. Each turn resolves the
 * suite's single {@code requestTemplate}/{@code inputBindings} against that turn's own scalar map — there is
 * no array-valued column projection. Turn count {@code N} is the length of the array; indices are contiguous
 * {@code 0..N-1}; the last turn is {@code turnIndex == N - 1}.
 *
 * <p>Each turn is persisted as its own {@link TestCaseRunResult}, sharing the case's {@code testCaseId}/
 * {@code testCaseName} and the multi-turn span's {@code traceId}, carrying {@code turnIndex}/{@code
 * totalTurns=N}, that turn's own scalar {@code testCaseData}, the full accumulated {@code requestBody}
 * actually sent, that turn's raw {@code responseBody}, and that turn's scalar {@code extractedColumns}/
 * {@code extractionWarnings}.
 *
 * <p>Contract: the resolved request body must be JSON with a top-level {@code messages} array; the assistant
 * reply is read from the hardcoded {@code choices[0].message} OpenAI path; turns are always non-streaming;
 * the loop is fail-fast — the first turn that fails after retries (or returns a 2xx with no assistant message
 * object) aborts the conversation. Completed turns persist as {@code SUCCESS} rows; the failing turn persists
 * as one {@code ERROR} row.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiTurnExecutor {

    private static final String MESSAGES_FIELD = "messages";

    private final ResolvedRequestService resolvedRequestService;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final JsonbMapper jsonbMapper;
    private final QuietJsonService jsonService;
    private final DeploymentTurnInvoker deploymentTurnInvoker;
    private final Clock clock;

    /**
     * Runs the full conversation for one test case, returning one {@link TestCaseRunResult} per executed
     * turn (fewer than {@code N} on early abort). The whole conversation runs inside the caller's single
     * worker task / semaphore permit; turns are sequential. {@code traceId} is the conversation span's id
     * (shared by every turn row).
     */
    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs) {

        final List<Map<String, Object>> turns = parseTurns(input.getMultiTurnData());
        if (turns.isEmpty()) {
            log.warn("Multi-turn test case {} has no readable turns", input.getTestCaseId());
            return List.of(buildEmptyTurnsErrorRow(input, context, runIndex, traceId, execStartedAtMs));
        }

        final List<InputBindingDto> bindings = input.getInputBindingsOverride() != null
                ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                : context.getSnapshotInputBindings();
        final RequestTemplateDto template = input.getRequestTemplateOverride() != null
                ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                : context.getSnapshotRequestTemplate();
        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final HttpMethod method = context.getSnapshotEndpointRef() != null
                ? context.getSnapshotEndpointRef().getMethod()
                : null;

        // The case's shared (test-case-level) data is frozen into the snapshot input's testCaseData; each
        // turn's effective view merges it with that turn's own per-turn map (per-turn keys win). This merged
        // view drives template resolution and is persisted as the turn row's testCaseData, so it also feeds
        // the conditional-metric dictionary and metric input downstream.
        final Map<String, Object> sharedData = parseSharedData(input.getTestCaseData());
        final int totalTurns = turns.size();
        final List<TestCaseRunResult> results = new ArrayList<>();
        final List<Object> history = new ArrayList<>();
        int turnIndex = 0;
        try {
            for (turnIndex = 0; turnIndex < totalTurns; turnIndex++) {
                final Map<String, Object> turnData = mergeSharedAndTurn(sharedData, turns.get(turnIndex));
                final TurnDefinition turn = new TurnDefinition(
                        turnIndex, context, template, bindings, turnData, deploymentId, method, responseColumns);

                final long turnStart = clock.millis();
                final TurnResult result = runTurn(turn, input.getTestCaseId(), history);
                final long turnEnd = clock.millis();

                history.addAll(result.messagesToAppend());

                if (result.control() == TurnControl.CONTINUE) {
                    results.add(buildTurnRow(
                            input,
                            context,
                            runIndex,
                            traceId,
                            turnStart,
                            turnEnd,
                            turnIndex,
                            totalTurns,
                            ExecutionStatus.SUCCESS,
                            result.outcome(),
                            result.requestBodyJson(),
                            result.extractedColumnsJson(),
                            result.extractionWarningsJson(),
                            turnData));
                } else {
                    final boolean requestIssued = result.outcome() != null;
                    if (requestIssued || !context.getCancellationSignal().get()) {
                        results.add(buildTurnRow(
                                input,
                                context,
                                runIndex,
                                traceId,
                                turnStart,
                                turnEnd,
                                turnIndex,
                                totalTurns,
                                result.status(),
                                result.outcome(),
                                result.requestBodyJson(),
                                "{}",
                                "[]",
                                turnData));
                    }
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Multi-turn run failed for test case {} in suite {} at turn {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    turnIndex,
                    e.getMessage(),
                    e);
            final long now = clock.millis();
            results.add(buildTurnRow(
                    input,
                    context,
                    runIndex,
                    traceId,
                    now,
                    now,
                    Math.min(turnIndex, totalTurns - 1),
                    totalTurns,
                    ExecutionStatus.ERROR,
                    null,
                    null,
                    "{}",
                    "[]",
                    mergeSharedAndTurn(sharedData, turns.get(Math.min(turnIndex, totalTurns - 1)))));
        }
        return results;
    }

    private TestCaseRunResult buildTurnRow(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long turnStart,
            long turnEnd,
            int turnIndex,
            int totalTurns,
            ExecutionStatus status,
            TurnOutcome outcome,
            String requestBodyJson,
            String extractedColumnsJson,
            String extractionWarningsJson,
            Map<String, Object> turnData) {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
                .testCaseData(jsonService.writeOrToString(turnData))
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
                .logDetails(null)
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    /**
     * Runs one turn without mutating shared state: it re-sends the accumulated {@code history} plus this
     * turn's new user message(s) non-streaming, and on a 2xx reads the assistant reply and extracts response
     * columns. {@code history} is read only to build the request body.
     */
    private TurnResult runTurn(TurnDefinition turn, UUID testCaseId, List<Object> history) {
        if (turn.context().getCancellationSignal().get()) {
            return TurnResult.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        final ResolvedRequestDto resolved =
                resolvedRequestService.resolve(turn.template(), turn.bindings(), turn.turnData());
        final ResolvedBodyDto resolvedBody = resolved.getBody();
        if (!(resolvedBody instanceof ResolvedJsonBodyDto jsonBody) || jsonBody.getContent() == null) {
            log.warn(
                    "Multi-turn turn {} for test case {} has no JSON body with a messages array",
                    turn.index(),
                    testCaseId);
            return TurnResult.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        final Map<String, Object> content = jsonBody.getContent();
        final Object turnMessages = content.get(MESSAGES_FIELD);
        if (!(turnMessages instanceof List<?> messages)) {
            log.warn(
                    "Multi-turn turn {} for test case {} resolved a non-array 'messages'; failing this test case",
                    turn.index(),
                    testCaseId);
            return TurnResult.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        final List<Object> newMessages = new ArrayList<>(messages);
        final List<Object> fullMessages = new ArrayList<>(history);
        fullMessages.addAll(newMessages);
        content.put(MESSAGES_FIELD, fullMessages);
        content.put("stream", false);

        final String requestBodyJson = jsonService.writeOrToString(content);
        final String path = urlBuilder.buildUrl(turn.deploymentId(), resolved.getUrl());
        final HttpHeaders headers = buildHeaders(resolved.getHeaders());
        final MultiValueMap<String, String> queryParams =
                DeploymentInvocationSupport.buildQueryParams(resolved.getQueryParams());
        final SerializedBody serialized = serializerRegistry.serialize(jsonBody);
        if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
            headers.setContentType(serialized.contentType());
        }

        final TurnOutcome outcome = deploymentTurnInvoker.invoke(
                turn.context(), turn.method(), path, headers, queryParams, serialized.body());
        if (outcome.status() != ExecutionStatus.SUCCESS) {
            return TurnResult.abortAfterRequest(outcome.status(), requestBodyJson, newMessages, outcome);
        }

        final JsonNode assistantMessage = extractAssistantMessage(outcome.responseBody());
        if (assistantMessage == null) {
            log.warn(
                    "Multi-turn turn {} for test case {} returned 2xx with no assistant message object",
                    turn.index(),
                    testCaseId);
            return TurnResult.abortAfterRequest(ExecutionStatus.ERROR, requestBodyJson, newMessages, outcome);
        }

        final List<Object> messagesToAppend = new ArrayList<>(newMessages);
        messagesToAppend.add(assistantMessage);
        final ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(turn.responseColumns(), outcome.responseBody());
        return TurnResult.completed(
                requestBodyJson,
                messagesToAppend,
                outcome,
                extraction.extractedColumns(),
                extraction.extractionWarnings());
    }

    /** Parses the case's shared (test-case-level) data map from the frozen snapshot input; empty when absent. */
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
     * Single degenerate ERROR row for a case whose {@code multi_turn_data} carries no readable turns
     * (defensive — validation rejects an empty array at write time). Emitted with {@code turn_index=0},
     * {@code total_turns=1} and the failure captured in {@code logDetails}.
     */
    private TestCaseRunResult buildEmptyTurnsErrorRow(
            TestCaseRunInput input, EvaluationContext context, int runIndex, String traceId, long execStartedAtMs) {
        final long now = clock.millis();
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .turnIndex(0)
                .totalTurns(1)
                .testCaseData(input.getTestCaseData())
                .requestBody(null)
                .responseBody(null)
                .responseStatusCode(null)
                .executionStatus(ExecutionStatus.ERROR)
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(now)
                .execDurationMs(now - execStartedAtMs)
                .traceId(traceId)
                .extractedColumns("{}")
                .extractionWarnings("[]")
                .retryCount(0)
                .logDetails(buildErrorLogDetails("Multi-turn case has no readable turns"))
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    private String buildErrorLogDetails(String message) {
        final var node = jsonService.createObjectNode();
        node.put("error", message);
        return jsonService.writeOrToString(node);
    }

    /**
     * Reads the full {@code choices[0].message} object (hardcoded OpenAI path) to append to history verbatim;
     * returns null when there is no such message object (missing {@code choices}, empty array, or a
     * {@code message} that is not a JSON object). A present message object with {@code content: null} is valid.
     */
    private JsonNode extractAssistantMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        final JsonNode root = jsonService.readTreeOrEmpty(responseBody);
        final JsonNode message = root.path("choices").path(0).path("message");
        return message.isObject() ? message : null;
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

    /** Everything one turn needs, ready to execute: its index, the per-turn scalar data, and the send context. */
    private record TurnDefinition(
            int index,
            EvaluationContext context,
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            Map<String, Object> turnData,
            String deploymentId,
            HttpMethod method,
            List<ResponseColumnDefinitionDto> responseColumns) {}

    private enum TurnControl {
        CONTINUE,
        ABORT
    }

    /**
     * Result of one turn, applied by the caller: {@code messagesToAppend} is added to the running history
     * and, for a completed turn, the caller persists a SUCCESS row using {@code extractedColumnsJson} and
     * {@code extractionWarningsJson}. {@code control} tells the loop whether to continue or abort (with the
     * terminal {@code status}). When {@code outcome} is non-null the turn issued its HTTP request.
     */
    private record TurnResult(
            TurnControl control,
            ExecutionStatus status,
            String requestBodyJson,
            TurnOutcome outcome,
            List<Object> messagesToAppend,
            String extractedColumnsJson,
            String extractionWarningsJson) {

        static TurnResult abortBeforeRequest(ExecutionStatus status) {
            return new TurnResult(TurnControl.ABORT, status, null, null, List.of(), null, null);
        }

        static TurnResult abortAfterRequest(
                ExecutionStatus status, String requestBodyJson, List<Object> messagesToAppend, TurnOutcome outcome) {
            return new TurnResult(TurnControl.ABORT, status, requestBodyJson, outcome, messagesToAppend, null, null);
        }

        static TurnResult completed(
                String requestBodyJson,
                List<Object> messagesToAppend,
                TurnOutcome outcome,
                String extractedColumnsJson,
                String extractionWarningsJson) {
            return new TurnResult(
                    TurnControl.CONTINUE,
                    ExecutionStatus.SUCCESS,
                    requestBodyJson,
                    outcome,
                    messagesToAppend,
                    extractedColumnsJson,
                    extractionWarningsJson);
        }
    }
}
