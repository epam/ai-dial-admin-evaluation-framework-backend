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
 * Multi-turn executor for {@code DEPLOYMENT} suites. Drives a sequence of chat-completions
 * turns for one assembled multiTurn, accumulating {@code messages} history and re-sending the full
 * history each turn. A multiTurn is an <b>ordered group of discrete test-case rows</b> (one row per turn,
 * keyed by {@code multi_turn_id}/{@code turn_index}), frozen into the assembled input's {@code turns} JSON
 * at snapshot time. Each turn resolves the suite's single {@code requestTemplate}/{@code inputBindings}
 * against that turn's own <b>scalar</b> row {@code data} — there is no array-valued column projection.
 * Turn count {@code N} is the number of frozen (surviving) turns.
 *
 * <p>Each turn is persisted as its own {@link TestCaseRunResult}, carrying that turn's own row identity
 * ({@code testCaseId}/{@code testCaseName}), {@code turn_index} (authored 0-based) / {@code total_turns}
 * ({@code N}), the per-turn scalar {@code testCaseData}, the full accumulated {@code requestBody} actually
 * sent for that turn, that turn's raw {@code responseBody}, and that turn's scalar {@code extractedColumns}/
 * {@code extractionWarnings}. All rows of a multiTurn share the multiTurn span's {@code traceId}.
 *
 * <p>Contract: the resolved request body must be JSON with a top-level {@code messages} array; the
 * assistant reply is read from the hardcoded {@code choices[0].message} OpenAI path; turns are always
 * invoked non-streaming; the loop is fail-fast — the first turn that fails after retries (or returns a 2xx
 * with no assistant message object) aborts the multiTurn. Completed turns are persisted as {@code
 * SUCCESS} rows; the failing turn is persisted as one {@code ERROR} row (both with {@code total_turns = N}).
 * Broken multiTurns are detected at snapshot time and never reach this executor — they are turned into a
 * single {@code 0/0} ERROR row by {@link EvaluationWorker}.
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
     * Runs the full multiTurn for one test case, returning one {@link TestCaseRunResult} per turn (fewer
     * than {@code N} on early abort; a single degenerate {@code ERROR} row on a data-shape problem). The whole
     * multiTurn executes inside the caller's single worker task / semaphore permit; turns are sequential.
     * {@code traceId} is the multiTurn span's id (shared by every turn row).
     */
    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs) {

        final List<FrozenTurn> turns = parseTurns(input.getTurns());
        if (turns.isEmpty()) {
            log.warn(
                    "Multi-turn {} for test case {} has no readable frozen turns",
                    input.getMultiTurnId(),
                    input.getTestCaseId());
            return List.of(buildMultiTurnErrorRow(input, context, runIndex, traceId, execStartedAtMs));
        }

        final List<InputBindingDto> bindings = input.getInputBindingsOverride() != null
                ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                : context.getSnapshotInputBindings();

        final int totalTurns = turns.size();

        /*
         Authored indices are preserved (no renumbering); the last surviving turn's authored index is the
         max, which drives turn.last correctly even when survivors are non-contiguous.
        */
        final int lastTurnIndex =
                turns.stream().mapToInt(FrozenTurn::turnIndex).max().orElse(0);

        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final HttpMethod method = context.getSnapshotEndpointRef() != null
                ? context.getSnapshotEndpointRef().getMethod()
                : null;
        final RequestTemplateDto template = input.getRequestTemplateOverride() != null
                ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                : context.getSnapshotRequestTemplate();

        final List<TestCaseRunResult> results = new ArrayList<>();
        final List<Object> history = new ArrayList<>();
        FrozenTurn current = turns.get(0);
        try {
            for (int i = 0; i < totalTurns; i++) {
                current = turns.get(i);
                final Map<String, Object> turnData = current.data();
                final var turn = new TurnDefinition(
                        current.turnIndex(),
                        current.testCaseId(),
                        context,
                        template,
                        bindings,
                        turnData,
                        deploymentId,
                        method,
                        responseColumns);

                final long turnStart = clock.millis();
                final TurnResult result = runTurn(turn, history);
                final long turnEnd = clock.millis();

                history.addAll(result.messagesToAppend());

                if (result.control() == TurnControl.CONTINUE) {
                    results.add(buildTurnRow(
                            current,
                            context,
                            runIndex,
                            traceId,
                            turnStart,
                            turnEnd,
                            current.turnIndex(),
                            totalTurns,
                            lastTurnIndex,
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
                                current,
                                context,
                                runIndex,
                                traceId,
                                turnStart,
                                turnEnd,
                                current.turnIndex(),
                                totalTurns,
                                lastTurnIndex,
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
                    current.testCaseId(),
                    context.getSuiteId(),
                    current.turnIndex(),
                    e.getMessage(),
                    e);
            final long now = clock.millis();
            results.add(buildTurnRow(
                    current,
                    context,
                    runIndex,
                    traceId,
                    now,
                    now,
                    current.turnIndex(),
                    totalTurns,
                    lastTurnIndex,
                    ExecutionStatus.ERROR,
                    null,
                    null,
                    "{}",
                    "[]",
                    current.data()));
        }
        return results;
    }

    private TestCaseRunResult buildTurnRow(
            FrozenTurn turn,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long turnStart,
            long turnEnd,
            int turnIndex,
            int totalTurns,
            int lastTurnIndex,
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
                .testCaseId(turn.testCaseId())
                .testCaseName(turn.testCaseName())
                .runIndex(runIndex)
                .turnIndex(turnIndex)
                .totalTurns(totalTurns)
                .lastTurnIndex(lastTurnIndex)
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
     * Runs one multiTurn turn without mutating shared state: it re-sends the accumulated {@code history}
     * plus this turn's new user message(s) non-streaming, and on a 2xx reads the assistant reply and extracts
     * response columns. {@code history} is read only to build the request body.
     */
    private TurnResult runTurn(TurnDefinition turn, List<Object> history) {
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
                    turn.testCaseId());
            return TurnResult.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        final Map<String, Object> content = jsonBody.getContent();
        final Object turnMessages = content.get(MESSAGES_FIELD);
        if (!(turnMessages instanceof List<?> messages)) {
            log.warn(
                    "Multi-turn turn {} for test case {} resolved a non-array 'messages'; failing this test case",
                    turn.index(),
                    turn.testCaseId());
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
                    turn.testCaseId());
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

    /**
     * Parses the assembled input's frozen {@code turns} JSON ({@code [{testCaseId, testCaseName, turnIndex,
     * data}, ...]}) into ordered {@link FrozenTurn}s. Returns an empty list for a null/blank/non-array
     * payload (surfaced by the caller as a single {@code 0/0} ERROR row). The snapshot phase writes the
     * turns already ordered by {@code turn_index}.
     */
    private List<FrozenTurn> parseTurns(String turnsJson) {
        if (turnsJson == null || turnsJson.isBlank()) {
            return List.of();
        }
        final JsonNode root = jsonService.readTreeOrEmpty(turnsJson);
        if (!root.isArray()) {
            return List.of();
        }
        final List<FrozenTurn> turns = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            final JsonNode idNode = node.get("testCaseId");
            final JsonNode turnIndexNode = node.get("turnIndex");
            if (idNode == null || turnIndexNode == null) {
                continue;
            }
            final UUID testCaseId = UUID.fromString(idNode.asString());
            final String testCaseName =
                    node.hasNonNull("testCaseName") ? node.get("testCaseName").asString() : null;
            final int turnIndex = turnIndexNode.asInt();
            final JsonNode dataNode = node.get("data");
            final Map<String, Object> data =
                    dataNode == null || dataNode.isNull() ? Map.of() : jsonService.readMapOrEmpty(dataNode.toString());
            turns.add(new FrozenTurn(testCaseId, testCaseName, turnIndex, data));
        }
        return turns;
    }

    /**
     * Single degenerate {@code ERROR} row for a multiTurn that carries no readable frozen turns
     * (defensive; the snapshot phase never emits such an input for a non-broken multiTurn):
     * {@code turn_index=0}, {@code total_turns=0} (distinguishing "never started" from a real single turn,
     * which is {@code 0/1}), with the failure captured in {@code logDetails}.
     */
    private TestCaseRunResult buildMultiTurnErrorRow(
            TestCaseRunInput input, EvaluationContext context, int runIndex, String traceId, long execStartedAtMs) {
        final String message = "MultiTurn has no readable turns";
        final long now = clock.millis();
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .turnIndex(0)
                .totalTurns(0)
                .lastTurnIndex(0)
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
                .logDetails(buildErrorLogDetails(message))
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

    /** One frozen turn of an assembled multiTurn: its own row identity, authored index, and scalar data. */
    private record FrozenTurn(UUID testCaseId, String testCaseName, int turnIndex, Map<String, Object> data) {}

    /** Everything one turn needs, ready to execute: its index, the per-turn scalar data, and the send context. */
    private record TurnDefinition(
            int index,
            UUID testCaseId,
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
     * and, for a completed turn, the caller persists a SUCCESS row using {@code extractedColumnsJson}
     * (that turn's scalar object) and {@code extractionWarningsJson}. {@code control} tells the loop whether
     * to continue or abort (with the terminal {@code status}). When {@code outcome} is non-null the turn
     * issued its HTTP request.
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
