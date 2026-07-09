package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.client.mcp.McpTransport;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.service.domain.McpRequestResolver;
import com.epam.aidial.evaluation.service.domain.McpResponseSerializer;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.service.domain.ResolvedRequestService;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes a single test case: resolves request from snapshot template/bindings + input row data,
 * calls endpoint, captures response/timing, extracts columns, and builds TestCaseRunResult.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvaluationWorker {

    private final ResolvedRequestService resolvedRequestService;
    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final DialCoreUrlBuilder urlBuilder;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final ResponseColumnExtractor responseColumnExtractor;
    private final ObjectMapper objectMapper;
    private final JsonbMapper jsonbMapper;
    private final EvaluationRunProperties evaluationRunProperties;
    private final OpenTelemetry openTelemetry;
    private final McpToolInvoker mcpToolInvoker;
    private final McpRequestResolver mcpRequestResolver;
    private final McpResponseSerializer mcpResponseSerializer;
    private final Clock clock;
    private final SseEventParser sseEventParser;
    private final SseEventProcessingProperties sseEventProcessingProperties;
    private final MultiTurnConversationExecutor multiTurnConversationExecutor;
    private final QuietJsonService jsonService;

    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns) {
        Span span = openTelemetry
                .getTracer("com.epam.aidial.evaluation")
                .spanBuilder("eval.testcase.execute")
                .setAttribute("testcase.id", input.getTestCaseId().toString())
                .setAttribute("testcase.name", input.getTestCaseName())
                .setAttribute("run.index", String.valueOf(runIndex))
                .setAttribute("eval.run.id", context.getRunId().toString())
                .setAttribute("eval.suite.id", context.getSuiteId().toString())
                .startSpan();
        long execStartedAtMs = clock.millis();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;

        try (Scope scope = span.makeCurrent()) {
            // Check suite type for MCP branching
            if (context.getSuiteType() == SuiteType.MCP_TOOL) {
                return List.of(executeMcp(input, context, runIndex, responseColumns, span, traceId, execStartedAtMs));
            }

            // Multi-turn conversation suites delegate to the turn-loop executor (single permit per conversation),
            // returning one result row per turn.
            if (context.isSnapshotMultiTurn()) {
                return multiTurnConversationExecutor.execute(
                        input, context, runIndex, responseColumns, traceId, execStartedAtMs);
            }

            // Parse test case data for template resolution
            Map<String, Object> testCaseData = jsonService.readMapOrEmpty(input.getTestCaseData());

            // Resolve effective template and bindings (per-test-case overrides take priority)
            RequestTemplateDto effectiveTemplate = input.getRequestTemplateOverride() != null
                    ? jsonbMapper.mapRequestTemplate(input.getRequestTemplateOverride())
                    : context.getSnapshotRequestTemplate();
            List<InputBindingDto> effectiveBindings = input.getInputBindingsOverride() != null
                    ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                    : context.getSnapshotInputBindings();

            // Resolve request using snapshot template and input row data
            ResolvedRequestDto resolved =
                    resolvedRequestService.resolve(effectiveTemplate, effectiveBindings, testCaseData);

            // Build URL from snapshot deployment ref
            String deploymentId = context.getSnapshotDeploymentRef() != null
                    ? context.getSnapshotDeploymentRef().getId()
                    : null;
            String endpointUrl = resolved.getUrl();
            String path = urlBuilder.buildUrl(deploymentId, endpointUrl);

            // Build HTTP method from snapshot endpoint ref
            HttpMethod method = context.getSnapshotEndpointRef() != null
                    ? context.getSnapshotEndpointRef().getMethod()
                    : null;

            // Build headers (filter blacklisted) — traceparent injected by RestClient interceptor
            HttpHeaders headers = buildHeaders(resolved.getHeaders(), context);

            // Build query params
            MultiValueMap<String, String> queryParams =
                    DeploymentInvocationSupport.buildQueryParams(resolved.getQueryParams());

            // Serialize body via content-type-aware registry
            ResolvedBodyDto resolvedBody = resolved.getBody();
            Object body = null;
            if (resolvedBody != null) {
                SerializedBody serialized = serializerRegistry.serialize(resolvedBody);
                // Skip setting Content-Type for multipart — RestClient auto-generates boundary
                if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
                    headers.setContentType(serialized.contentType());
                }
                body = serialized.body();
            }

            // Invoke deployment with retries
            return List.of(invokeWithRetries(
                    input,
                    context,
                    runIndex,
                    responseColumns,
                    traceId,
                    execStartedAtMs,
                    method,
                    path,
                    headers,
                    queryParams,
                    body,
                    resolvedBody));

        } catch (Exception e) {
            // Request resolution error
            log.warn(
                    "Request resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    e.getMessage(),
                    e);
            long now = clock.millis();
            String errorBody = buildErrorEnvelope("REQUEST_RESOLUTION_ERROR", e.getMessage());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            return List.of(buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    execStartedAtMs,
                    now,
                    now - execStartedAtMs,
                    ExecutionStatus.ERROR,
                    null,
                    errorBody,
                    responseColumns));
        } finally {
            span.end();
        }
    }

    private TestCaseRunResult invokeWithRetries(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body,
            ResolvedBodyDto resolvedBody) {

        int maxRetries = context.getMaxRetries();
        long retryDelayMs = context.getRetryDelayMs();
        double multiplier = context.getRetryBackoffMultiplier();
        long maxRetryDelay = context.getMaxRetryDelayMs();

        // Serialize resolved body (not wire format) for analytics storage
        String requestBodyJson = serializeBodyForAnalytics(resolvedBody);
        List<RetryAttemptLog> retryAttempts = new ArrayList<>();
        TestCaseRunResult lastResult = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                // Check cancellation before retry
                if (context.getCancellationSignal().get()) {
                    break;
                }

                long delay = DeploymentInvocationSupport.nextBackoffDelayMs(
                        attempt, retryDelayMs, multiplier, maxRetryDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (context.getCancellationSignal().get()) {
                    break;
                }
            }

            long attemptStartMs = clock.millis();
            lastResult = invokeSingle(
                    input,
                    context,
                    runIndex,
                    responseColumns,
                    traceId,
                    execStartedAtMs,
                    method,
                    path,
                    headers,
                    queryParams,
                    body);

            if (!DeploymentInvocationSupport.isRetryable(
                    lastResult.getExecutionStatus(), lastResult.getResponseStatusCode(), attempt, maxRetries)) {
                break;
            }

            // Record failed attempt for logDetails
            long attemptDurationMs = clock.millis() - attemptStartMs;
            String errorType = resolveErrorType(lastResult.getExecutionStatus());
            retryAttempts.add(
                    new RetryAttemptLog(attempt + 1, lastResult.getResponseStatusCode(), errorType, attemptDurationMs));
        }

        // Set retry tracking on final result
        int retryCount = retryAttempts.size();
        String logDetails = retryCount > 0 ? buildLogDetailsJson(retryAttempts) : null;

        return buildResult(
                input,
                context,
                runIndex,
                traceId,
                lastResult.getExecStartedAtMs(),
                lastResult.getExecCompletedAtMs(),
                lastResult.getExecDurationMs(),
                lastResult.getExecutionStatus(),
                lastResult.getResponseStatusCode(),
                lastResult.getResponseBody(),
                requestBodyJson,
                retryCount,
                logDetails,
                responseColumns);
    }

    private String resolveErrorType(ExecutionStatus status) {
        return switch (status) {
            case TIMEOUT -> "TIMEOUT";
            case ERROR -> "NETWORK_ERROR";
            default -> "HTTP_ERROR";
        };
    }

    private String buildLogDetailsJson(List<RetryAttemptLog> attempts) {
        try {
            var root = objectMapper.createObjectNode();
            var array = objectMapper.createArrayNode();
            for (RetryAttemptLog attempt : attempts) {
                var node = objectMapper.createObjectNode();
                node.put("attemptIndex", attempt.attemptIndex());
                if (attempt.statusCode() != null) {
                    node.put("statusCode", attempt.statusCode());
                } else {
                    node.putNull("statusCode");
                }
                node.put("errorType", attempt.errorType());
                node.put("durationMs", attempt.durationMs());
                array.add(node);
            }
            root.set("retryAttempts", array);
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException e) {
            log.warn("Failed to serialize logDetails: {}", e.getMessage(), e);
            return null;
        }
    }

    private record RetryAttemptLog(int attemptIndex, Integer statusCode, String errorType, long durationMs) {}

    // ---- MCP execution path ----

    private TestCaseRunResult executeMcp(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            Span span,
            String traceId,
            long execStartedAtMs) {
        try {
            // Use pre-deserialized typed DTOs from context
            McpDeploymentReferenceDto mcpRef = context.getMcpDeploymentRefDto();
            ToolReferenceDto toolRef = context.getToolRefDto();
            ArgumentTemplateDto argumentTemplate = context.getArgumentTemplateDto();

            // Resolve effective bindings: test case override takes priority over suite-level
            List<InputBindingDto> effectiveBindings = input.getInputBindingsOverride() != null
                    ? jsonbMapper.mapInputBindings(input.getInputBindingsOverride())
                    : context.getInputBindings();

            // Parse test case data
            Map<String, Object> testCaseData = jsonService.readMapOrEmpty(input.getTestCaseData());

            // Resolve arguments
            McpRequestResolver.ResolutionResult resolutionResult =
                    mcpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData);
            Map<String, Object> resolvedArgs = resolutionResult.getArguments();
            String requestBodyJson = jsonService.writeOrToString(resolvedArgs);

            // Invoke with retries
            return invokeMcpWithRetries(
                    input,
                    context,
                    runIndex,
                    responseColumns,
                    traceId,
                    execStartedAtMs,
                    mcpRef,
                    toolRef,
                    resolvedArgs,
                    requestBodyJson);
        } catch (RuntimeException e) {
            log.warn(
                    "MCP request resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    e.getMessage(),
                    e);
            long now = clock.millis();
            String errorBody = buildErrorEnvelope("MCP_RESOLUTION_ERROR", e.getMessage());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    execStartedAtMs,
                    now,
                    now - execStartedAtMs,
                    ExecutionStatus.ERROR,
                    null,
                    errorBody,
                    responseColumns);
        }
    }

    private TestCaseRunResult invokeMcpWithRetries(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs,
            McpDeploymentReferenceDto mcpRef,
            ToolReferenceDto toolRef,
            Map<String, Object> resolvedArgs,
            String requestBodyJson) {

        int maxRetries = context.getMaxRetries();
        long retryDelayMs = context.getRetryDelayMs();
        double multiplier = context.getRetryBackoffMultiplier();
        long maxRetryDelay = context.getMaxRetryDelayMs();

        List<RetryAttemptLog> retryAttempts = new ArrayList<>();
        TestCaseRunResult lastResult = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                if (context.getCancellationSignal().get()) {
                    break;
                }
                long delay = DeploymentInvocationSupport.nextBackoffDelayMs(
                        attempt, retryDelayMs, multiplier, maxRetryDelay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (context.getCancellationSignal().get()) {
                    break;
                }
            }

            long attemptStartMs = clock.millis();
            lastResult = invokeMcpSingle(
                    input, context, runIndex, responseColumns, traceId, execStartedAtMs, mcpRef, toolRef, resolvedArgs);

            if (!shouldRetryMcp(lastResult, attempt, maxRetries)) {
                break;
            }

            long attemptDurationMs = clock.millis() - attemptStartMs;
            String errorType = resolveErrorType(lastResult.getExecutionStatus());
            retryAttempts.add(
                    new RetryAttemptLog(attempt + 1, lastResult.getResponseStatusCode(), errorType, attemptDurationMs));
        }

        int retryCount = retryAttempts.size();
        String logDetails = retryCount > 0 ? buildLogDetailsJson(retryAttempts) : null;

        return buildResult(
                input,
                context,
                runIndex,
                traceId,
                lastResult.getExecStartedAtMs(),
                lastResult.getExecCompletedAtMs(),
                lastResult.getExecDurationMs(),
                lastResult.getExecutionStatus(),
                lastResult.getResponseStatusCode(),
                lastResult.getResponseBody(),
                requestBodyJson,
                retryCount,
                logDetails,
                responseColumns);
    }

    private TestCaseRunResult invokeMcpSingle(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs,
            McpDeploymentReferenceDto mcpRef,
            ToolReferenceDto toolRef,
            Map<String, Object> resolvedArgs) {

        long callStartMs = clock.millis();
        String token = context.getToken();

        try {
            McpTransport transport =
                    mcpRef.getTransport() != null ? mcpRef.getTransport() : McpTransport.STREAMABLE_HTTP;
            CallToolResult result =
                    mcpToolInvoker.callTool(mcpRef.getId(), toolRef.getName(), resolvedArgs, token, transport);

            String responseBody = mcpResponseSerializer.serialize(result);

            // Check response size
            if (responseBody != null) {
                long bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8).length;
                if (bodyBytes > context.getMaxResponseSizeBytes()) {
                    responseBody = truncateResponse(responseBody, context.getMaxResponseSizeBytes());
                    long now = clock.millis();
                    return buildResult(
                            input,
                            context,
                            runIndex,
                            traceId,
                            callStartMs,
                            now,
                            now - callStartMs,
                            ExecutionStatus.ERROR,
                            null,
                            responseBody,
                            responseColumns);
                }
            }

            // Determine execution status from isError flag
            ExecutionStatus status =
                    (result.isError() != null && result.isError()) ? ExecutionStatus.FAILED : ExecutionStatus.SUCCESS;

            long now = clock.millis();
            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    callStartMs,
                    now,
                    now - callStartMs,
                    status,
                    null,
                    responseBody,
                    responseColumns);

        } catch (McpInvocationException e) {
            long now = clock.millis();
            ExecutionStatus status;
            if (e.getStatusCode() == 504) {
                status = ExecutionStatus.TIMEOUT;
            } else {
                status = ExecutionStatus.ERROR;
            }
            String errorBody = buildErrorEnvelope("MCP_INVOCATION_ERROR", e.getMessage());
            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    callStartMs,
                    now,
                    now - callStartMs,
                    status,
                    null,
                    errorBody,
                    responseColumns);
        } catch (RuntimeException e) {
            long now = clock.millis();
            ExecutionStatus status =
                    DeploymentInvocationSupport.isTimeoutException(e) ? ExecutionStatus.TIMEOUT : ExecutionStatus.ERROR;
            String errorBody = buildErrorEnvelope("MCP_INVOCATION_ERROR", e.getMessage());
            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    callStartMs,
                    now,
                    now - callStartMs,
                    status,
                    null,
                    errorBody,
                    responseColumns);
        }
    }

    private boolean shouldRetryMcp(TestCaseRunResult result, int attempt, int maxRetries) {
        if (attempt >= maxRetries) {
            return false;
        }
        ExecutionStatus status = result.getExecutionStatus();
        // Retryable: TIMEOUT, ERROR (transport/network)
        // Non-retryable: FAILED (isError=true - tool-level error), SUCCESS
        return status == ExecutionStatus.TIMEOUT || status == ExecutionStatus.ERROR;
    }

    // ---- HTTP/Deployment execution path ----

    private TestCaseRunResult invokeSingle(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns,
            String traceId,
            long execStartedAtMs,
            HttpMethod method,
            String path,
            HttpHeaders headers,
            MultiValueMap<String, String> queryParams,
            Object body) {

        long callStartMs = clock.millis();

        try (DeploymentInvocationResult result =
                deploymentInvoker.invokeWithStreaming(method, path, headers, queryParams, body)) {

            int statusCode = result.statusCode();
            ExecutionStatus execStatus = DeploymentInvocationSupport.resolveExecutionStatus(statusCode);

            String responseBody;

            if (result.streaming()) {
                // Streaming response: idle timeout = per-run request timeout; absolute cap = global property.
                StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                        sseEventParser,
                        objectMapper,
                        context.getRequestTimeoutMs(),
                        sseEventProcessingProperties.getMaxTotalDurationMs(),
                        context.getMaxResponseSizeBytes());
                accumulator.accumulate(result.eventStream());

                responseBody = accumulator.getResponseBody();

                if (accumulator.getExecutionStatus() != ExecutionStatus.SUCCESS) {
                    execStatus = accumulator.getExecutionStatus();
                }
            } else {
                // Non-streaming response
                responseBody = jsonService.writeOrToString(result.body());

                // Check size limit
                if (responseBody != null) {
                    long bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8).length;
                    if (bodyBytes > context.getMaxResponseSizeBytes()) {
                        responseBody = truncateResponse(responseBody, context.getMaxResponseSizeBytes());
                        execStatus = ExecutionStatus.ERROR;
                    }
                }
            }

            long execCompletedAtMs = clock.millis();
            long execDurationMs = execCompletedAtMs - callStartMs;

            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    callStartMs,
                    execCompletedAtMs,
                    execDurationMs,
                    execStatus,
                    statusCode,
                    responseBody,
                    responseColumns);

        } catch (Exception e) {
            long now = clock.millis();
            long duration = now - callStartMs;

            ExecutionStatus status;
            if (DeploymentInvocationSupport.isTimeoutException(e)) {
                status = ExecutionStatus.TIMEOUT;
            } else {
                status = ExecutionStatus.ERROR;
            }

            String errorBody = buildErrorEnvelope("INVOCATION_ERROR", e.getMessage());
            return buildResult(
                    input,
                    context,
                    runIndex,
                    traceId,
                    callStartMs,
                    now,
                    duration,
                    status,
                    null,
                    errorBody,
                    responseColumns);
        }
    }

    private HttpHeaders buildHeaders(List<KeyValueTemplateDto> resolvedHeaders, EvaluationContext context) {
        HttpHeaders headers = new HttpHeaders();
        Set<String> blacklist = evaluationRunProperties.getExecution().getHeaderBlacklist().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (resolvedHeaders != null) {
            for (KeyValueTemplateDto kv : resolvedHeaders) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    if (blacklist.contains(kv.getKey().toLowerCase())) {
                        log.debug("Skipping blacklisted header: {}", kv.getKey());
                        continue;
                    }
                    headers.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return headers;
    }

    private TestCaseRunResult buildResult(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            long execCompletedAtMs,
            long execDurationMs,
            ExecutionStatus executionStatus,
            Integer responseStatusCode,
            String responseBody,
            List<ResponseColumnDefinitionDto> responseColumns) {
        return buildResult(
                input,
                context,
                runIndex,
                traceId,
                execStartedAtMs,
                execCompletedAtMs,
                execDurationMs,
                executionStatus,
                responseStatusCode,
                responseBody,
                null,
                0,
                null,
                responseColumns);
    }

    private TestCaseRunResult buildResult(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            String traceId,
            long execStartedAtMs,
            long execCompletedAtMs,
            long execDurationMs,
            ExecutionStatus executionStatus,
            Integer responseStatusCode,
            String responseBody,
            String requestBodyJson,
            int retryCount,
            String logDetails,
            List<ResponseColumnDefinitionDto> responseColumns) {

        ResponseColumnExtractor.ExtractionResult extraction =
                responseColumnExtractor.extract(responseColumns, responseBody);

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
                .requestBody(requestBodyJson)
                .responseBody(responseBody)
                .responseStatusCode(responseStatusCode)
                .executionStatus(executionStatus)
                .execStartedAtMs(execStartedAtMs)
                .execCompletedAtMs(execCompletedAtMs)
                .execDurationMs(execDurationMs)
                .traceId(traceId)
                .extractedColumns(extraction.extractedColumns())
                .extractionWarnings(extraction.extractionWarnings())
                .retryCount(retryCount)
                .logDetails(logDetails)
                .createdAtMs(context.getCreatedAtMs())
                .build();
    }

    private String serializeBodyForAnalytics(ResolvedBodyDto body) {
        if (body == null) {
            return null;
        }
        // For JSON bodies, store just the content map (no contentType wrapper)
        Object toSerialize = body instanceof ResolvedJsonBodyDto jsonBody ? jsonBody.getContent() : body;
        return jsonService.writeOrToString(toSerialize);
    }

    private String truncateResponse(String responseBody, long maxBytes) {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return responseBody;
        }
        String truncated = new String(bytes, 0, (int) maxBytes, StandardCharsets.UTF_8);
        try {
            return objectMapper.writeValueAsString(truncated);
        } catch (JacksonException e) {
            return "\"<response truncated>\"";
        }
    }

    private String buildErrorEnvelope(String code, String message) {
        try {
            var error = objectMapper.createObjectNode();
            error.put("code", code);
            error.put("message", message != null ? message : "Unknown error");
            var root = objectMapper.createObjectNode();
            root.set("error", error);
            return objectMapper.writeValueAsString(root);
        } catch (JacksonException e) {
            return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"serialization failed\"}}";
        }
    }
}
