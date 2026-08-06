package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.service.McpRequestResolver;
import com.epam.aidial.evaluation.runner.service.McpResponseSerializer;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.util.EvalBaggage;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches a single test case to the right execution path: every DEPLOYMENT HTTP case (single-request or
 * multi-request chain, single-turn or multi-turn alike) runs through {@link RequestChainExecutor}; an
 * MCP_TOOL case resolves arguments and calls the tool directly here, with its own retry loop and response
 * handling. {@code responseColumns} is used only by the MCP branch — a DEPLOYMENT chain derives every
 * request's own response columns from the run snapshot (see {@link RequestChainExecutor}).
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class EvaluationWorker {

    private final ResponseColumnExtractor responseColumnExtractor;
    private final ObjectMapper objectMapper;
    private final RunnerJsonbMapper jsonbMapper;
    private final OpenTelemetry openTelemetry;
    private final McpToolInvoker mcpToolInvoker;
    private final McpRequestResolver mcpRequestResolver;
    private final McpResponseSerializer mcpResponseSerializer;
    private final Clock clock;
    private final RequestChainExecutor requestChainExecutor;

    public List<TestCaseRunResult> execute(
            TestCaseRunInput input,
            EvaluationContext context,
            int runIndex,
            List<ResponseColumnDefinitionDto> responseColumns) {
        Span span = openTelemetry
                .getTracer(TracingConstants.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder(TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE)
                .setAttribute(
                        TracingConstants.TESTCASE_ID, input.getTestCaseId().toString())
                .setAttribute(TracingConstants.TESTCASE_NAME, input.getTestCaseName())
                .setAttribute(TracingConstants.RUN_INDEX, String.valueOf(runIndex))
                .setAttribute(TracingConstants.EVAL_RUN_ID, context.getRunId().toString())
                .setAttribute(
                        TracingConstants.EVAL_SUITE_ID, context.getSuiteId().toString())
                .setAttribute(TracingConstants.EVAL_PHASE, TracingConstants.PHASE_EXECUTION)
                .startSpan();
        long execStartedAtMs = clock.millis();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;

        Baggage baggage = EvalBaggage.withExecutionContext(
                context.getRunId(), context.getSuiteId(), input.getTestCaseId(), runIndex);
        Context traceContext = Context.current().with(span).with(baggage);
        try (Scope _ = traceContext.makeCurrent()) {
            // Check suite type for MCP branching
            if (context.getSuiteType() == SuiteType.MCP_TOOL) {
                return List.of(executeMcp(input, context, runIndex, responseColumns, span, traceId, execStartedAtMs));
            }

            List<TestCaseRunResult> results =
                    requestChainExecutor.execute(input, context, runIndex, traceId, execStartedAtMs);

            results.stream()
                    .filter(row -> row.getExecutionStatus() == ExecutionStatus.ERROR)
                    .findFirst()
                    .ifPresent(errorRow -> span.setStatus(
                            StatusCode.ERROR,
                            errorRow.getLogDetails() != null
                                    ? errorRow.getLogDetails()
                                    : "Test case execution failed"));
            return results;

        } catch (Exception e) {
            // Request resolution error
            log.warn(
                    "Request resolution failed for test case {} in suite {}: {}",
                    input.getTestCaseId(),
                    context.getSuiteId(),
                    e.getMessage(),
                    e);
            long now = clock.millis();
            String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                    ExecutionErrorCodes.REQUEST_RESOLUTION_ERROR, e.getMessage(), objectMapper);
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
            Map<String, Object> testCaseData = parseTestCaseData(input.getTestCaseData());

            // Resolve arguments
            McpRequestResolver.ResolutionResult resolutionResult =
                    mcpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData);
            Map<String, Object> resolvedArgs = resolutionResult.getArguments();
            String requestBodyJson = serializeBody(resolvedArgs);

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
            String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                    ExecutionErrorCodes.MCP_RESOLUTION_ERROR, e.getMessage(), objectMapper);
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

        List<DeploymentInvocationSupport.RetryAttemptLog> retryAttempts = new ArrayList<>();
        TestCaseRunResult lastResult = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                if (context.getCancellationSignal().get()) {
                    break;
                }
                long delay = Math.min((long) (retryDelayMs * Math.pow(multiplier, attempt - 1)), maxRetryDelay);
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
            String errorType = DeploymentInvocationSupport.resolveErrorType(lastResult.getExecutionStatus());
            retryAttempts.add(new DeploymentInvocationSupport.RetryAttemptLog(
                    attempt + 1, lastResult.getResponseStatusCode(), errorType, attemptDurationMs));
        }

        int retryCount = retryAttempts.size();
        String logDetails = DeploymentInvocationSupport.buildRetryLogDetailsJson(retryAttempts, objectMapper);

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
            String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                    ExecutionErrorCodes.MCP_INVOCATION_ERROR, e.getMessage(), objectMapper);
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
            String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                    ExecutionErrorCodes.MCP_INVOCATION_ERROR, e.getMessage(), objectMapper);
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

    private Map<String, Object> parseTestCaseData(String data) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            log.warn("Failed to parse test case data: {}", e.getMessage(), e);
            return Map.of();
        }
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

    /**
     * Builds the persisted result row, extracting response columns with the {@code requestBodyJson}
     * already computed by the caller for {@code TestCaseRunResult.requestBody} fed uniformly into the
     * extractor's {@code $_request} frame binding (see {@link ResponseColumnExtractor}). Only used by the
     * MCP execution path here — DEPLOYMENT HTTP cases are built by {@link TurnLoopExecutor}. For an MCP
     * tool call, {@code requestBodyJson} is the serialized resolved tool arguments ({@link #serializeBody})
     * — a coherent "what was sent" value even though an MCP row has no HTTP request body in the
     * traditional sense.
     */
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
                responseColumnExtractor.extract(responseColumns, responseBody, requestBodyJson);

        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(context.getRunId())
                .testSuiteId(context.getSuiteId())
                .testCaseId(input.getTestCaseId())
                .testCaseName(input.getTestCaseName())
                .runIndex(runIndex)
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

    /**
     * Byte-truncates an oversize MCP response and JSON-escapes the remainder so the persisted
     * {@code response_body} stays valid JSON (same contract as the DEPLOYMENT path, which escapes the
     * {@link DeploymentInvocationSupport#truncateUtf8} result in {@link DeploymentTurnInvoker}).
     */
    private String truncateResponse(String responseBody, long maxBytes) {
        String truncated = DeploymentInvocationSupport.truncateUtf8(responseBody, maxBytes);
        if (responseBody.equals(truncated)) {
            return responseBody;
        }
        try {
            return objectMapper.writeValueAsString(truncated);
        } catch (JacksonException e) {
            log.warn("Failed to serialize truncated MCP response: {}", e.getMessage(), e);
            return "\"<response truncated>\"";
        }
    }
}
