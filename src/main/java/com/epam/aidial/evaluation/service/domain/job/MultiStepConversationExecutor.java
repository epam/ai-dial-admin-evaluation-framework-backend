package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.constants.ValidationConstants;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
 * a JSON array of per-step extraction maps. The full conversation remains recoverable from the last request
 * body (which carries the whole message history through the final user turn) plus this final response.
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
    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final EvaluationRunProperties evaluationRunProperties;
    private final JsonbMapper jsonbMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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
        final RequestTemplateDto template = input.getRequestTemplateOverride() != null
                ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                : context.getSnapshotRequestTemplate();

        // Turn count is derived per test case from the array-valued bound columns. A data problem here
        // (no array column, mismatched lengths, over the cap) fails only this test case.
        final TurnPlan turnPlan = resolveTurnCount(bindings, testCaseData);
        if (turnPlan.hasError()) {
            log.warn(
                    "Multi-step turn resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    turnPlan.error());
            return errorResult(input, context, runIndex, traceId, execStartedAtMs, turnPlan.error());
        }

        final String deploymentId = context.getSnapshotDeploymentRef() != null
                ? context.getSnapshotDeploymentRef().getId()
                : null;
        final HttpMethod method = context.getSnapshotEndpointRef() != null
                ? context.getSnapshotEndpointRef().getMethod()
                : null;

        final List<Object> history = new ArrayList<>();
        final ArrayNode extractedPerStep = objectMapper.createArrayNode();

        ExecutionStatus finalStatus = ExecutionStatus.SUCCESS;
        Integer lastStatusCode = null;
        String lastRequestBodyJson = null;
        String lastResponseBodyJson = null;
        int lastRetryCount = 0;

        try {
            for (int i = 0; i < turnPlan.turnCount(); i++) {
                if (context.getCancellationSignal().get()) {
                    finalStatus = ExecutionStatus.ERROR;
                    break;
                }

                // (1) Project this turn's data: array-valued bound columns → element i; scalars/constants broadcast.
                final Map<String, Object> perTurnData = projectTurnData(testCaseData, turnPlan.iteratingFields(), i);
                final ResolvedRequestDto resolved = resolvedRequestService.resolve(template, bindings, perTurnData);
                final ResolvedBodyDto resolvedBody = resolved.getBody();
                if (!(resolvedBody instanceof ResolvedJsonBodyDto jsonBody) || jsonBody.getContent() == null) {
                    log.warn(
                            "Multi-step step {} for test case {} has no JSON body with a messages array",
                            i,
                            input.getTestCaseId());
                    finalStatus = ExecutionStatus.ERROR;
                    break;
                }

                // (2) Append this step's new turn (template messages) verbatim to the running history.
                final Map<String, Object> content = jsonBody.getContent();
                final Object turnMessages = content.get(MESSAGES_FIELD);
                if (turnMessages instanceof List<?> turn) {
                    history.addAll(turn);
                }
                // (3) Overwrite messages with the full history; force non-streaming.
                content.put(MESSAGES_FIELD, new ArrayList<>(history));
                content.put("stream", false);
                lastRequestBodyJson = serializeBody(content);

                final String path = urlBuilder.buildUrl(deploymentId, resolved.getUrl());
                final HttpHeaders headers = buildHeaders(resolved.getHeaders());
                final MultiValueMap<String, String> queryParams = buildQueryParams(resolved.getQueryParams());
                final SerializedBody serialized = serializerRegistry.serialize(jsonBody);
                if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
                    headers.setContentType(serialized.contentType());
                }

                final StepOutcome outcome =
                        invokeWithRetries(context, method, path, headers, queryParams, serialized.body());
                lastStatusCode = outcome.statusCode();
                lastRetryCount = outcome.retryCount();
                lastResponseBodyJson = outcome.responseBody();

                if (outcome.status() != ExecutionStatus.SUCCESS) {
                    // Fail-fast: keep the partial history (incl. this failed turn's user message); no extraction.
                    finalStatus = outcome.status();
                    break;
                }

                // (4) Append the assistant reply — the full choices[0].message object, verbatim — to history.
                // Absence of a message object (not merely missing content) aborts the conversation.
                final JsonNode assistantMessage = extractAssistantMessage(outcome.responseBody());
                if (assistantMessage == null) {
                    log.warn(
                            "Multi-step step {} for test case {} returned 2xx with no assistant message object",
                            i,
                            input.getTestCaseId());
                    finalStatus = ExecutionStatus.ERROR;
                    break;
                }
                history.add(assistantMessage);

                // (5) Extract response columns for this completed step and accumulate the per-step array.
                final ResponseColumnExtractor.ExtractionResult extraction =
                        responseColumnExtractor.extract(responseColumns, outcome.responseBody());
                extractedPerStep.add(readTree(extraction.extractedColumns()));
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

        final long execCompletedAtMs = clock.millis();
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
                .testCaseData(input.getTestCaseData())
                .requestBody(lastRequestBodyJson)
                .responseBody(lastResponseBodyJson)
                .responseStatusCode(lastStatusCode)
                .executionStatus(finalStatus)
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(execCompletedAtMs)
                .execDurationMs(execCompletedAtMs - execStartedAtMs)
                .traceId(traceId)
                .extractedColumns(serializeBody(extractedPerStep))
                .extractionWarnings("[]")
                .retryCount(lastRetryCount)
                .logDetails(null)
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    /** Resolved turn count for a test case: a valid count with the iterating field names, or an error message. */
    private record TurnPlan(int turnCount, Set<String> iteratingFields, String error) {
        static TurnPlan of(int turnCount, Set<String> iteratingFields) {
            return new TurnPlan(turnCount, iteratingFields, null);
        }

        static TurnPlan error(String message) {
            return new TurnPlan(0, Set.of(), message);
        }

        boolean hasError() {
            return error != null;
        }
    }

    /**
     * Derives the per-test-case turn count from the array-valued columns referenced by {@code dataField}
     * bindings. All such columns must share a single non-zero length within the cap; scalar columns and
     * {@code constantValue} bindings are ignored here (they broadcast). Returns an error plan for a
     * length mismatch, an empty/absent array column, or a count over {@code MAX_CONVERSATION_STEPS}.
     */
    private TurnPlan resolveTurnCount(List<InputBindingDto> bindings, Map<String, Object> data) {
        final Map<String, Integer> arrayFieldLengths = new LinkedHashMap<>();
        if (bindings != null) {
            for (InputBindingDto binding : bindings) {
                if (binding == null
                        || binding.getDataField() == null
                        || binding.getDataField().isBlank()) {
                    continue;
                }
                if (data.get(binding.getDataField()) instanceof List<?> list) {
                    arrayFieldLengths.put(binding.getDataField(), list.size());
                }
            }
        }
        if (arrayFieldLengths.isEmpty()) {
            return TurnPlan.error(
                    "Multi-step suite requires at least one array-valued bound column; none found in test-case data");
        }
        final Set<Integer> distinctLengths = new HashSet<>(arrayFieldLengths.values());
        if (distinctLengths.size() > 1) {
            return TurnPlan.error("Array-valued bound columns must have equal length; got " + arrayFieldLengths);
        }
        final int turnCount = distinctLengths.iterator().next();
        if (turnCount == 0) {
            return TurnPlan.error("Array-valued bound columns are empty; no conversation turns to run");
        }
        if (turnCount > ValidationConstants.MAX_CONVERSATION_STEPS) {
            return TurnPlan.error("Conversation turn count " + turnCount + " exceeds the maximum of "
                    + ValidationConstants.MAX_CONVERSATION_STEPS);
        }
        return TurnPlan.of(turnCount, arrayFieldLengths.keySet());
    }

    /** Builds turn {@code i}'s data: iterating (array-valued) fields → element {@code i}; all others unchanged. */
    private Map<String, Object> projectTurnData(Map<String, Object> data, Set<String> iteratingFields, int i) {
        final Map<String, Object> perTurn = new LinkedHashMap<>(data);
        for (String field : iteratingFields) {
            if (data.get(field) instanceof List<?> list) {
                perTurn.put(field, list.get(i));
            }
        }
        return perTurn;
    }

    /** Persists a single {@code ERROR} result for a per-test-case data problem, without aborting the run. */
    private TestCaseRunResult errorResult(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            String message) {
        final long execCompletedAtMs = clock.millis();
        return TestCaseRunResult.builder()
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
                .execCompletedAtMs(execCompletedAtMs)
                .execDurationMs(execCompletedAtMs - execStartedAtMs)
                .traceId(traceId)
                .extractedColumns("[]")
                .extractionWarnings("[]")
                .retryCount(0)
                .logDetails(buildErrorLogDetails(message))
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    private String buildErrorLogDetails(String message) {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("error", message);
        return serializeBody(node);
    }

    /** Per-step outcome carrier (one HTTP turn after retries). */
    private record StepOutcome(ExecutionStatus status, Integer statusCode, String responseBody, int retryCount) {}

    private StepOutcome invokeWithRetries(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {

        final int maxRetries = context.getMaxRetries();
        final long retryDelayMs = context.getRetryDelayMs();
        final double multiplier = context.getRetryBackoffMultiplier();
        final long maxRetryDelay = context.getMaxRetryDelayMs();

        StepOutcome last = null;
        int retries = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                if (context.getCancellationSignal().get()) {
                    break;
                }
                final long delay = Math.min((long) (retryDelayMs * Math.pow(multiplier, attempt - 1)), maxRetryDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (context.getCancellationSignal().get()) {
                    break;
                }
                retries++;
            }

            last = invokeSingle(context, method, path, headers, queryParams, body);
            if (!shouldRetry(last, attempt, maxRetries)) {
                break;
            }
        }
        return new StepOutcome(last.status(), last.statusCode(), last.responseBody(), retries);
    }

    private StepOutcome invokeSingle(
            EvaluationContext context,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {
        try (DeploymentInvocationResult result =
                deploymentInvoker.invokeWithStreaming(method, path, headers, queryParams, body)) {
            final int statusCode = result.statusCode();
            ExecutionStatus status = resolveExecutionStatus(statusCode);

            // Multi-step is non-streaming only: a streaming response cannot be consumed here.
            if (result.streaming()) {
                return new StepOutcome(ExecutionStatus.ERROR, statusCode, null, 0);
            }

            String responseBody = serializeBody(result.body());
            if (responseBody != null
                    && responseBody.getBytes(StandardCharsets.UTF_8).length > context.getMaxResponseSizeBytes()) {
                status = ExecutionStatus.ERROR;
            }
            return new StepOutcome(status, statusCode, responseBody, 0);
        } catch (Exception e) {
            final ExecutionStatus status = isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            return new StepOutcome(status, null, null, 0);
        }
    }

    private boolean shouldRetry(StepOutcome outcome, int attempt, int maxRetries) {
        if (attempt >= maxRetries) {
            return false;
        }
        final ExecutionStatus status = outcome.status();
        final Integer statusCode = outcome.statusCode();
        if (status == ExecutionStatus.TIMEOUT) {
            return true;
        }
        if (status == ExecutionStatus.ERROR && statusCode == null) {
            return true;
        }
        return statusCode != null && (statusCode == 429 || statusCode >= 500);
    }

    private ExecutionStatus resolveExecutionStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return ExecutionStatus.SUCCESS;
        }
        if (statusCode == 401 || statusCode == 403) {
            return ExecutionStatus.ERROR;
        }
        return ExecutionStatus.FAILED;
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

    private boolean isTimeoutException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            final String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            log.warn("Failed to parse extracted columns JSON: {}", e.getMessage(), e);
            return objectMapper.createObjectNode();
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
