package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Multi-step (multi-turn) conversation executor (POC). Drives a data-driven sequence of chat-completions
 * turns for a single test case, accumulating {@code messages} history and re-sending the full history each
 * turn. The suite uses its single {@code inputBindings}; per-turn variation comes from array-valued
 * test-case columns bound by those bindings. The number of turns is derived <b>per test case</b> as the
 * common length of the array-valued bound columns; scalar columns and {@code constantValue} bindings are
 * broadcast (reused unchanged) on every turn. Turn {@code i} resolves the template with element {@code i}
 * of each array-valued column.
 *
 * <p>Returns a single {@link TestCaseRunResult} that reuses the existing columns: {@code responseBody} =
 * the last turn's raw response body (preserving its technical fields, e.g. {@code id}/{@code usage}/{@code
 * model}) — mirroring how {@code requestBody} holds the last turn's raw request; {@code extractedColumns} =
 * a column-major JSON object mapping each response column name to an array of that column's per-step
 * extracted values (one element per completed step, {@code null} for a step whose extraction failed).
 * The full conversation remains recoverable from the last request body (which carries the whole message
 * history through the final user turn) plus this final response.
 *
 * <p>Contract: the resolved request body must be JSON with a top-level {@code messages} array; the assistant
 * reply is read from the hardcoded {@code choices[0].message.content} OpenAI path; steps are always invoked
 * non-streaming; the loop is fail-fast — the first step that fails after retries (or returns a 2xx with no
 * extractable assistant reply) aborts the conversation, persisting partial history and partial per-step
 * extractions. A per-test-case data problem (no array-valued bound column, mismatched array lengths, or a
 * turn count exceeding the cap) fails only that test case with an {@code ERROR} result; other test cases in
 * the run proceed.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MultiStepConversationExecutor {

    private static final String MESSAGES_FIELD = "messages";

    private final ResolvedRequestService resolvedRequestService;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final JsonbMapper jsonbMapper;
    private final ObjectMapper objectMapper;
    private final ConversationTurnPlanner turnPlanner;
    private final MultiStepResultAssembler resultAssembler;
    private final DeploymentTurnInvoker deploymentTurnInvoker;

    /**
     * Runs the full conversation for one test case. The whole conversation executes inside the caller's
     * single worker task / semaphore permit; turns are sequential. {@code traceId} is the worker span's
     * id (shared by every turn). The turn count is derived from this test case's array-valued bound columns.
     */
    public TestCaseRunResult execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs) {

        final List<InputBindingDto> bindings = input.getInputBindingsOverride() != null
                ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                : context.getSnapshotInputBindings();
        final Map<String, Object> testCaseData = parseTestCaseData(input.getTestCaseData());

        // Turn count is derived per test case from the array-valued bound columns. A data problem here
        // (no array column, mismatched lengths, over the cap) fails only this test case.
        final TurnPlan turnPlan = turnPlanner.plan(bindings, testCaseData);
        if (turnPlan.hasError()) {
            log.warn(
                    "Multi-step turn resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    turnPlan.error());
            return resultAssembler.dataError(input, context, runIndex, traceId, execStartedAtMs, turnPlan.error());
        }

        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final HttpMethod method = context.getSnapshotEndpointRef() != null
                ? context.getSnapshotEndpointRef().getMethod()
                : null;

        // Column-major accumulation: column name → array of that column's per-step extracted values.
        final RequestTemplateDto template = input.getRequestTemplateOverride() != null
                ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                : context.getSnapshotRequestTemplate();

        final var turnInputs = new TurnInputs(
                context, input, template, bindings, testCaseData, turnPlan, deploymentId, method, responseColumns);

        ExecutionStatus finalStatus = ExecutionStatus.SUCCESS;
        Integer lastStatusCode = null;
        String lastRequestBodyJson = null;
        String lastResponseBodyJson = null;
        int lastRetryCount = 0;

        final List<Object> history = new ArrayList<>();
        final var columnAccumulator = new MultiStepColumnAccumulator(objectMapper);
        try {
            for (int i = 0; i < turnPlan.turnCount(); i++) {
                final TurnStep step = runTurn(turnInputs, i, history, columnAccumulator);
                // Once a turn issued its HTTP request, the last-turn trackers reflect that turn verbatim
                // (including nulls, e.g. a timeout); a turn that aborts before the request leaves them untouched.
                if (step.httpAttempted()) {
                    lastRequestBodyJson = step.requestBodyJson();
                    lastStatusCode = step.statusCode();
                    lastResponseBodyJson = step.responseBodyJson();
                    lastRetryCount = step.retryCount();
                }
                if (step.control() == TurnControl.ABORT) {
                    finalStatus = step.status();
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Multi-step conversation failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    e.getMessage(),
                    e);
            finalStatus = ExecutionStatus.ERROR;
        }

        final var outcome = new ConversationOutcome(
                finalStatus,
                lastStatusCode,
                lastRequestBodyJson,
                lastResponseBodyJson,
                lastRetryCount,
                columnAccumulator.toJson());
        return resultAssembler.success(input, context, runIndex, traceId, execStartedAtMs, outcome);
    }

    /** Per-conversation invariants shared by every turn (everything except the running history / step index). */
    private record TurnInputs(
            EvaluationContext context,
            TestCaseRunInput input,
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            Map<String, Object> testCaseData,
            TurnPlan turnPlan,
            String deploymentId,
            HttpMethod method,
            List<ResponseColumnDefinitionDto> responseColumns) {}

    private enum TurnControl {
        CONTINUE,
        ABORT
    }

    /**
     * Outcome of a single turn. {@code control} tells the loop whether to continue or abort (with the
     * terminal {@code status}). {@code httpAttempted} is true once the turn issued its HTTP request — the
     * caller then adopts this turn's request/response/status/retry verbatim; a turn that aborts earlier
     * leaves the last-turn trackers untouched.
     */
    private record TurnStep(
            TurnControl control,
            ExecutionStatus status,
            boolean httpAttempted,
            String requestBodyJson,
            Integer statusCode,
            String responseBodyJson,
            Integer retryCount) {

        /** Aborted before any HTTP request (cancellation, missing JSON body, non-array messages). */
        static TurnStep abortBeforeRequest(ExecutionStatus status) {
            return new TurnStep(TurnControl.ABORT, status, false, null, null, null, null);
        }

        /** Aborted after the HTTP request (non-success response, or 2xx with no assistant message). */
        static TurnStep abortAfterRequest(ExecutionStatus status, String requestBodyJson, StepOutcome outcome) {
            return new TurnStep(
                    TurnControl.ABORT,
                    status,
                    true,
                    requestBodyJson,
                    outcome.statusCode(),
                    outcome.responseBody(),
                    outcome.retryCount());
        }

        /** Turn completed: assistant reply appended and response columns extracted. */
        static TurnStep completed(String requestBodyJson, StepOutcome outcome) {
            return new TurnStep(
                    TurnControl.CONTINUE,
                    ExecutionStatus.SUCCESS,
                    true,
                    requestBodyJson,
                    outcome.statusCode(),
                    outcome.responseBody(),
                    outcome.retryCount());
        }
    }

    /**
     * Runs one conversation turn: projects turn {@code i}'s data, appends the new user turn to {@code history},
     * re-sends the full history non-streaming, and on a 2xx appends the assistant reply and extracts response
     * columns into {@code columnAccumulator}. Mutates the shared {@code history} and {@code columnAccumulator};
     * returns a {@link TurnStep} describing whether the conversation should continue.
     */
    private TurnStep runTurn(
            TurnInputs turnInputs, int i, List<Object> history, MultiStepColumnAccumulator columnAccumulator) {
        if (turnInputs.context().getCancellationSignal().get()) {
            return TurnStep.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        // (1) Project this turn's data: array-valued bound columns → element i; scalars/constants broadcast.
        final Map<String, Object> perTurnData = turnInputs.turnPlan().project(turnInputs.testCaseData(), i);
        final ResolvedRequestDto resolved =
                resolvedRequestService.resolve(turnInputs.template(), turnInputs.bindings(), perTurnData);
        final ResolvedBodyDto resolvedBody = resolved.getBody();
        if (!(resolvedBody instanceof ResolvedJsonBodyDto jsonBody) || jsonBody.getContent() == null) {
            log.warn(
                    "Multi-step step {} for test case {} has no JSON body with a messages array",
                    i,
                    turnInputs.input().getTestCaseId());
            return TurnStep.abortBeforeRequest(ExecutionStatus.ERROR);
        }

        // (2) Append this step's new turn (template messages) verbatim to the running history.
        final Map<String, Object> content = jsonBody.getContent();
        final Object turnMessages = content.get(MESSAGES_FIELD);
        if (!(turnMessages instanceof List<?> messages)) {
            log.warn(
                    "Multi-step step {} for test case {} resolved a non-array 'messages'; failing this test case",
                    i,
                    turnInputs.input().getTestCaseId());
            return TurnStep.abortBeforeRequest(ExecutionStatus.ERROR);
        }
        history.addAll(messages);
        // (3) Overwrite messages with the full history; force non-streaming.
        content.put(MESSAGES_FIELD, new ArrayList<>(history));
        content.put("stream", false);
        final String requestBodyJson = serializeBody(content);

        final String path = urlBuilder.buildUrl(turnInputs.deploymentId(), resolved.getUrl());
        final HttpHeaders headers = buildHeaders(resolved.getHeaders());
        final MultiValueMap<String, String> queryParams = buildQueryParams(resolved.getQueryParams());
        final SerializedBody serialized = serializerRegistry.serialize(jsonBody);
        if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
            headers.setContentType(serialized.contentType());
        }

        final StepOutcome outcome = deploymentTurnInvoker.invoke(
                turnInputs.context(), turnInputs.method(), path, headers, queryParams, serialized.body());
        if (outcome.status() != ExecutionStatus.SUCCESS) {
            // Fail-fast: keep the partial history (incl. this failed turn's user message); no extraction.
            return TurnStep.abortAfterRequest(outcome.status(), requestBodyJson, outcome);
        }

        // (4) Append the assistant reply — the full choices[0].message object, verbatim — to history.
        // Absence of a message object (not merely missing content) aborts the conversation.
        final JsonNode assistantMessage = extractAssistantMessage(outcome.responseBody());
        if (assistantMessage == null) {
            log.warn(
                    "Multi-step step {} for test case {} returned 2xx with no assistant message object",
                    i,
                    turnInputs.input().getTestCaseId());
            return TurnStep.abortAfterRequest(ExecutionStatus.ERROR, requestBodyJson, outcome);
        }
        history.add(assistantMessage);

        // (5) Extract response columns for this completed step and append each column's value to its array.
        final ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(turnInputs.responseColumns(), outcome.responseBody());
        columnAccumulator.addStep(turnInputs.responseColumns(), extraction.extractedColumns());

        return TurnStep.completed(requestBodyJson, outcome);
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
        try {
            final JsonNode root = objectMapper.readTree(responseBody);
            final JsonNode message = root.path("choices").path(0).path("message");
            return message.isObject() ? message : null;
        } catch (JacksonException e) {
            log.warn("Failed to parse multi-step response body for assistant message: {}", e.getMessage(), e);
            return null;
        }
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

    private MultiValueMap<String, String> buildQueryParams(List<KeyValueTemplateDto> resolvedParams) {
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        if (resolvedParams != null) {
            for (KeyValueTemplateDto kv : resolvedParams) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    queryParams.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return queryParams;
    }

    private Map<String, Object> parseTestCaseData(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(data, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            log.warn("Failed to parse test case data: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    private String serializeBody(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            return body.toString();
        }
    }
}
