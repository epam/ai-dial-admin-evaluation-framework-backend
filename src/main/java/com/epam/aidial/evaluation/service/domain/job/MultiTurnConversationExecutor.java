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
 * Multi-turn (conversational) executor for {@code DEPLOYMENT} suites. Drives a data-driven sequence of
 * chat-completions turns for a single test case, accumulating {@code messages} history and re-sending the
 * full history each turn. The suite uses its single {@code inputBindings}; per-turn variation comes from
 * array-valued test-case columns bound by those bindings. Turn count {@code N} is derived <b>per test
 * case</b> as the common length of the array-valued bound columns; scalar columns and {@code constantValue}
 * bindings are broadcast (reused unchanged) on every turn.
 *
 * <p>Unlike the previous POC, each turn is persisted as its <b>own scalar</b> {@link TestCaseRunResult}
 * (turn {@code i} resolves the template with element {@code i} of each array-valued column). A row carries
 * {@code turn_index}/{@code total_turns} (planned {@code N}), the per-turn projected scalar {@code
 * testCaseData}, the full accumulated {@code requestBody} actually sent for that turn, that turn's raw
 * {@code responseBody}, and that turn's scalar {@code extractedColumns}/{@code extractionWarnings}. All
 * rows of a conversation share the conversation span's {@code traceId}.
 *
 * <p>Contract: the resolved request body must be JSON with a top-level {@code messages} array; the
 * assistant reply is read from the hardcoded {@code choices[0].message} OpenAI path; turns are always
 * invoked non-streaming; the loop is fail-fast — the first turn that fails after retries (or returns a 2xx
 * with no assistant message object) aborts the conversation. Completed turns are persisted as {@code
 * SUCCESS} rows; the failing turn is persisted as one {@code ERROR} row (both with {@code total_turns = N}).
 * A per-test-case data problem (no array-valued bound column, mismatched array lengths, or a turn count
 * exceeding the cap) fails only that test case with a single degenerate {@code ERROR} row
 * ({@code turn_index=0}, {@code total_turns=0}); other test cases in the run proceed.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiTurnConversationExecutor {

    private static final String MESSAGES_FIELD = "messages";

    private final ResolvedRequestService resolvedRequestService;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final JsonbMapper jsonbMapper;
    private final QuietJsonService jsonService;
    private final ConversationTurnPlanner turnPlanner;
    private final DeploymentTurnInvoker deploymentTurnInvoker;
    private final Clock clock;

    /**
     * Runs the full conversation for one test case, returning one {@link TestCaseRunResult} per turn (fewer
     * than {@code N} on early abort; a single degenerate {@code ERROR} row on a data-shape problem). The whole
     * conversation executes inside the caller's single worker task / semaphore permit; turns are sequential.
     * {@code traceId} is the conversation span's id (shared by every turn row).
     */
    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs) {

        final List<InputBindingDto> bindings = input.getInputBindingsOverride() != null
                ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                : context.getSnapshotInputBindings();
        final Map<String, Object> testCaseData = jsonService.readMapOrEmpty(input.getTestCaseData());

        // Turn count is derived per test case. A data problem here (no array column, mismatched lengths,
        // over the cap) fails only this test case with a single degenerate 0/0 ERROR row.
        final TurnPlan turnPlan = turnPlanner.plan(bindings, testCaseData);
        if (turnPlan.hasError()) {
            log.warn(
                    "Multi-turn turn resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    turnPlan.error());
            return List.of(buildDataErrorRow(input, context, runIndex, traceId, execStartedAtMs, turnPlan.error()));
        }

        final int totalTurns = turnPlan.turnCount();
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
        int currentTurn = 0;
        try {
            for (int i = 0; i < totalTurns; i++) {
                currentTurn = i;
                final Map<String, Object> turnData = turnPlan.project(testCaseData, i);
                final var turn = new TurnDefinition(
                        i,
                        input.getTestCaseId(),
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
                            input,
                            context,
                            runIndex,
                            traceId,
                            turnStart,
                            turnEnd,
                            i,
                            totalTurns,
                            ExecutionStatus.SUCCESS,
                            result.outcome(),
                            result.requestBodyJson(),
                            result.extractedColumnsJson(),
                            result.extractionWarningsJson(),
                            turnData));
                } else {
                    // ABORT. Persist an ERROR row for the failing turn — unless the abort was a pre-request
                    // cancellation (no synthetic rows for cancelled cases). A turn that issued its request
                    // (outcome != null) is always a real failure and gets a row.
                    final boolean requestIssued = result.outcome() != null;
                    if (requestIssued || !context.getCancellationSignal().get()) {
                        results.add(buildTurnRow(
                                input,
                                context,
                                runIndex,
                                traceId,
                                turnStart,
                                turnEnd,
                                i,
                                totalTurns,
                                result.status(),
                                result.outcome(),
                                result.requestBodyJson(),
                                "{}", // nothing to extract
                                "[]", // nothing to warn about, since no extraction
                                turnData));
                    }
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Multi-turn conversation failed for test case {} in suite {} at turn {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    currentTurn,
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
                    currentTurn,
                    totalTurns,
                    ExecutionStatus.ERROR,
                    null,
                    null,
                    "{}",
                    "[]",
                    turnPlan.project(testCaseData, currentTurn)));
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
     * Runs one conversation turn without mutating shared state: it re-sends the accumulated {@code history}
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

        // Re-send the full accumulated history plus this turn's new user message(s); force non-streaming.
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
            // Fail-fast: keep the partial history (incl. this failed turn's user message); no extraction.
            return TurnResult.abortAfterRequest(outcome.status(), requestBodyJson, newMessages, outcome);
        }

        // Absence of a message object (not merely missing content) aborts the conversation.
        final JsonNode assistantMessage = extractAssistantMessage(outcome.responseBody());
        if (assistantMessage == null) {
            log.warn(
                    "Multi-turn turn {} for test case {} returned 2xx with no assistant message object",
                    turn.index(),
                    turn.testCaseId());
            return TurnResult.abortAfterRequest(ExecutionStatus.ERROR, requestBodyJson, newMessages, outcome);
        }

        // Append the assistant reply (the full choices[0].message object, verbatim) after the user turn.
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
     * Single degenerate {@code ERROR} row for a per-test-case data-shape problem (no turn ran):
     * {@code turn_index=0}, {@code total_turns=0} (distinguishing "never started" from a real single turn,
     * which is {@code 0/1}), with the failure captured in {@code logDetails}.
     */
    private TestCaseRunResult buildDataErrorRow(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            String message) {
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

    /** Everything one turn needs, ready to execute: its index, the projected per-turn data, and the send context. */
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
