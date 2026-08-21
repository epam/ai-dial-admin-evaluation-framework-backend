package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpTransport;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
import com.epam.aidial.evaluation.runner.job.DeploymentInvocationSupport;
import com.epam.aidial.evaluation.runner.job.ExecutionErrorCodes;
import com.epam.aidial.evaluation.runner.job.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.job.SseEvent;
import com.epam.aidial.evaluation.runner.job.SseEventParser;
import com.epam.aidial.evaluation.runner.job.SseParseResult;
import com.epam.aidial.evaluation.runner.job.StreamingResponseAccumulator;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.runner.service.McpRequestResolver;
import com.epam.aidial.evaluation.runner.service.McpResponseSerializer;
import com.epam.aidial.evaluation.runner.service.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.service.SerializedBody;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.SseEventDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutCoreResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class TryItOutService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final ResolvedRequestService resolvedRequestService;
    private final RequestResolver requestResolver;
    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final McpToolInvoker mcpToolInvoker;
    private final McpRequestResolver mcpRequestResolver;
    private final McpResponseSerializer mcpResponseSerializer;
    private final DialCoreUrlBuilder urlBuilder;
    private final JsonbMapper jsonbMapper;
    private final ObjectMapper objectMapper;
    private final RequestBodySerializerRegistry serializerRegistry;
    private final OpenTelemetry openTelemetry;
    private final GrafanaLinkBuilder grafanaLinkBuilder;
    private final Clock clock;
    private final SseEventParser sseEventParser;
    private final EvaluationRunProperties evaluationRunProperties;
    private final DialCoreProperties dialCoreProperties;
    private final SseEventProcessingProperties sseEventProcessingProperties;
    private final ResponseColumnExtractor responseColumnExtractor;

    public TryItOutResponseDto tryWithTestCase(UUID testSuiteId, UUID testCaseId) {
        TestSuite suite = loadSuite(testSuiteId);

        if (isMcpSuite(suite)) {
            McpDeploymentReferenceDto mcpRef = jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef());
            ToolReferenceDto toolRef = jsonbMapper.mapToolRef(suite.getToolRef());
            ArgumentTemplateDto argumentTemplate = jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate());
            validateMcpPreconditions(mcpRef, toolRef);

            TestCase testCase = testCaseRepository
                    .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                    .orElseThrow(() -> new EntityNotFoundException("TestCase " + testCaseId + " not found in dataset "
                            + suite.getDatasetId() + " (referenced by suite " + testSuiteId + ")"));

            if (testCase.getMultiTurnData() != null) {
                throw new InvalidOperationException("Cannot try out: MCP suites do not support multi-turn test cases");
            }

            // Suite-level bindings are the effective bindings — per-test-case overrides no longer exist
            List<InputBindingDto> effectiveBindings = jsonbMapper.mapInputBindings(suite.getInputBindings());

            Map<String, Object> testCaseData = parseTestCaseData(testCase.getData());
            McpRequestResolver.ResolutionResult resolutionResult =
                    mcpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData);
            validateMcpResolutionResult(resolutionResult);
            return invokeMcpAndBuildResponse(mcpRef, toolRef, resolutionResult.getArguments(), testSuiteId);
        }

        final DeploymentReferenceDto deploymentRef = jsonbMapper.map(suite.getDeploymentRef());
        final EndpointContractDto endpointRef = jsonbMapper.mapEndpointContract(suite.getEndpointRef());
        // Deserialized once here and handed to the planner, so a single try-out never parses the suite's
        // additionalRequests JSONB twice.
        final List<RequestDefinitionDto> additionalRequests =
                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests());
        // Preconditions (incl. per-chain-element) run on the already-loaded suite BEFORE planning, so a
        // misconfigured suite combined with a nonexistent testCaseId still yields 400, not 404 (design D7).
        validateChainPreconditions(deploymentRef, endpointRef, suite.getRequestTemplate(), additionalRequests);

        final ResolvedRequestService.ChainPlan plan =
                resolvedRequestService.planChain(testSuiteId, testCaseId, additionalRequests);
        if (plan.totalInvocations() <= 1) {
            // Single-request single-turn fast path, unchanged except the additive extraction fields (D5/D6).
            final ResolvedRequestDto resolved = resolvedRequestService.resolveRequest(testSuiteId, testCaseId);
            validateResolutionResult(resolved);
            final List<ResponseColumnDefinitionDto> responseColumns =
                    plan.requestPlans().getFirst().spec().responseColumns();
            return invokeAndBuildResponse(
                    resolved, deploymentRef, endpointRef.getMethod(), testSuiteId, responseColumns);
        }

        return runChain(plan, deploymentRef, testSuiteId, null);
    }

    /**
     * Executes every planned invocation of the suite's request chain sequentially — outer loop over the
     * plan's requests, inner loop over each request's turns — fail-fast: the first invocation that
     * resolves to a non-2xx status or a request-body JSONata evaluation failure stops the remaining turns
     * of its request AND every later request, becoming the returned top-level result and the last entry
     * of {@code history}. One frame accumulates monotonically across turns AND requests (later key wins),
     * mirroring {@code TurnLoopExecutor.mergeAccumulated} / {@code RequestChainExecutor}'s threading: a
     * request's first turn resolves against everything earlier requests extracted, and a turn whose
     * extraction fails to re-produce a column does not erase the previous turn's value.
     *
     * <p>Each invocation uses its own spec's {@code endpointRef} HTTP method. When {@code
     * bindingsOverride} is non-null (variables mode, design D8) it wholesale-replaces every request's own
     * {@code inputBindings}.
     */
    private TryItOutResponseDto runChain(
            ResolvedRequestService.ChainPlan plan,
            DeploymentReferenceDto deploymentRef,
            UUID testSuiteId,
            List<InputBindingDto> bindingsOverride) {
        Map<String, Object> frame = Map.of();
        final List<TryItOutResponseDto> history = new ArrayList<>();

        TurnInvocationResult current = null;
        RequestExecutionSpec currentSpec = null;
        int currentTurnIndex = 0;
        int currentTotalTurns = 1;
        ResponseColumnExtractor.ExtractionResult currentExtraction = null;

        chain:
        for (ResolvedRequestService.RequestPlan requestPlan : plan.requestPlans()) {
            final RequestExecutionSpec spec = requestPlan.spec();
            final List<InputBindingDto> bindings = bindingsOverride != null ? bindingsOverride : spec.inputBindings();
            final int totalTurns = requestPlan.turnDataList().size();

            for (int turnIndex = 0; turnIndex < totalTurns; turnIndex++) {
                final Map<String, Object> turnData = requestPlan.turnDataList().get(turnIndex);
                boolean failed;
                try {
                    final ResolvedRequestDto resolved =
                            requestResolver.resolveForRun(spec.requestTemplate(), bindings, turnData, frame);
                    validateResolutionResult(resolved);
                    current = invokeTurn(
                            resolved, deploymentRef, spec.endpointRef().getMethod(), testSuiteId);
                    // Covers both the HTTP status and a streaming response whose SSE parse did not
                    // complete (TIMEOUT/ERROR): extracting from a partial document would poison every
                    // later request's frame.
                    failed = current.status() != ExecutionStatus.SUCCESS;
                } catch (RequestBodyEvaluationException e) {
                    current = buildEvaluationFailureResult(e);
                    failed = true;
                }

                currentSpec = spec;
                currentTurnIndex = turnIndex;
                currentTotalTurns = totalTurns;
                currentExtraction = null;
                if (!failed && hasResponseColumns(spec)) {
                    // Deliberate divergence from the run path: extraction is skipped on the failing
                    // invocation — a preview has no persisted row to reconcile (design D4).
                    currentExtraction = extractColumns(spec.responseColumns(), current);
                    frame = mergeAccumulated(frame, currentExtraction.values());
                }
                history.add(buildInvocationEntry(current, spec, turnIndex, totalTurns, currentExtraction));

                if (failed) {
                    break chain;
                }
            }
        }

        final TryItOutResponseDto topLevel =
                buildInvocationEntry(current, currentSpec, currentTurnIndex, currentTotalTurns, currentExtraction);
        topLevel.setHistory(history);
        return topLevel;
    }

    /**
     * Builds one invocation's response envelope: base fields, identity stamps (request pair only when the
     * chain has more than one request, {@code requestName} additionally only when labelled; turn pair only
     * when this request planned more than one turn — mirroring {@code TurnLoopExecutor.stampIdentity}'s
     * guards), and the invocation's own extraction parsed verbatim from the extractor's null-preserving
     * JSON output into {@link JsonNode} fields (design D6).
     */
    private TryItOutResponseDto buildInvocationEntry(
            TurnInvocationResult result,
            RequestExecutionSpec spec,
            int turnIndex,
            int totalTurns,
            ResponseColumnExtractor.ExtractionResult extraction) {
        final boolean multiRequest = spec.totalRequests() > 1;
        final boolean multiTurn = totalTurns > 1;
        final boolean labelled = spec.name() != null && !spec.name().isBlank();
        return TryItOutResponseDto.builder()
                .resolvedRequest(result.resolvedRequest())
                .response(result.response())
                .durationMs(result.durationMs())
                .traceId(result.traceId())
                .grafanaTraceUrl(grafanaLinkBuilder.traceUrl(result.traceId()))
                .requestIndex(multiRequest ? spec.requestIndex() : null)
                .totalRequests(multiRequest ? spec.totalRequests() : null)
                .requestName(multiRequest && labelled ? spec.name() : null)
                .turnIndex(multiTurn ? turnIndex : null)
                .totalTurns(multiTurn ? totalTurns : null)
                .extractedColumns(extraction != null ? objectMapper.readTree(extraction.extractedColumns()) : null)
                .extractionWarnings(extraction != null ? objectMapper.readTree(extraction.extractionWarnings()) : null)
                .build();
    }

    private static boolean hasResponseColumns(RequestExecutionSpec spec) {
        return spec.responseColumns() != null && !spec.responseColumns().isEmpty();
    }

    /**
     * Folds one invocation's extracted values into the accumulated frame, later keys overwriting earlier
     * ones — the same semantics as {@code TurnLoopExecutor.mergeAccumulated}. Returns {@code base}
     * unchanged (no copy) when {@code values} is empty.
     */
    private static Map<String, Object> mergeAccumulated(Map<String, Object> base, Map<String, Object> values) {
        if (values.isEmpty()) {
            return base;
        }
        final Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(values);
        return merged;
    }

    /**
     * Extracts one invocation's response columns from the same document the run path would extract from:
     * for a streaming invocation that is the accumulator-assembled document (an OpenAI-mode stream's
     * concatenated deltas become a non-streaming {@code choices[0].message} document, so a column like
     * {@code choices[0].message.content} resolves here exactly as in a run), NOT the {@code
     * {"events":[…]}} envelope the response DTO displays. For a non-streaming invocation it is the parsed
     * response body itself, serialized lazily so a zero-column suite never pays for it.
     */
    private ResponseColumnExtractor.ExtractionResult extractColumns(
            List<ResponseColumnDefinitionDto> responseColumns, TurnInvocationResult result) {
        final String responseBodyJson = result.extractionDocumentJson() != null
                ? result.extractionDocumentJson()
                : writeOrNull(result.response().getBody());
        final String requestBodyJson =
                writeOrNull(resolvedBodyContent(result.resolvedRequest().getBody()));
        return responseColumnExtractor.extract(responseColumns, responseBodyJson, requestBodyJson);
    }

    private Object resolvedBodyContent(ResolvedBodyDto body) {
        return body instanceof ResolvedJsonBodyDto jsonBody ? jsonBody.getContent() : body;
    }

    private String writeOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(value);
    }

    private TurnInvocationResult buildEvaluationFailureResult(RequestBodyEvaluationException e) {
        final String errorBody = DeploymentInvocationSupport.buildErrorEnvelope(
                ExecutionErrorCodes.REQUEST_BODY_EVALUATION_ERROR, e.getMessage(), objectMapper);
        final TryItOutCoreResponseDto response = TryItOutCoreResponseDto.builder()
                .statusCode(0)
                .body(objectMapper.readTree(errorBody))
                .build();
        return new TurnInvocationResult(null, response, 0L, null, ExecutionStatus.ERROR, null);
    }

    /**
     * Carries one turn's invocation result before it becomes the returned top-level result.
     *
     * @param status the invocation's effective execution status — the HTTP status mapping, downgraded to
     *     the SSE parse status when a streaming response did not complete; anything but {@code SUCCESS}
     *     skips extraction and stops the chain
     * @param extractionDocumentJson the run-equivalent document to extract response columns from when it
     *     differs from the displayed {@code response.body} (streaming responses only); {@code null} means
     *     "serialize {@code response.body} lazily instead"
     */
    private record TurnInvocationResult(
            ResolvedRequestDto resolvedRequest,
            TryItOutCoreResponseDto response,
            Long durationMs,
            String traceId,
            ExecutionStatus status,
            String extractionDocumentJson) {}

    public TryItOutResponseDto tryWithVariables(UUID testSuiteId, Map<String, Object> variables) {
        TestSuite suite = loadSuite(testSuiteId);

        if (isMcpSuite(suite)) {
            McpDeploymentReferenceDto mcpRef = jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef());
            ToolReferenceDto toolRef = jsonbMapper.mapToolRef(suite.getToolRef());
            ArgumentTemplateDto argumentTemplate = jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate());
            validateMcpPreconditions(mcpRef, toolRef);

            List<InputBindingDto> bindings = convertVariablesToBindings(variables);
            McpRequestResolver.ResolutionResult resolutionResult =
                    mcpRequestResolver.resolveWithVariables(argumentTemplate, bindings, variables);
            validateMcpResolutionResult(resolutionResult);
            return invokeMcpAndBuildResponse(mcpRef, toolRef, resolutionResult.getArguments(), testSuiteId);
        }

        DeploymentReferenceDto deploymentRef = jsonbMapper.map(suite.getDeploymentRef());
        EndpointContractDto endpointRef = jsonbMapper.mapEndpointContract(suite.getEndpointRef());
        List<RequestDefinitionDto> additionalRequests =
                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests());
        validateChainPreconditions(deploymentRef, endpointRef, suite.getRequestTemplate(), additionalRequests);

        List<InputBindingDto> bindings = convertVariablesToBindings(variables);

        if (additionalRequests == null || additionalRequests.isEmpty()) {
            // Single-request fast path keeps the pre-existing lenient resolution (design D8), plus the
            // additive extraction fields (D6).
            RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
            ResolvedRequestDto resolved = requestResolver.resolve(template, bindings, Map.of());
            validateResolutionResult(resolved);
            return invokeAndBuildResponse(
                    resolved,
                    deploymentRef,
                    endpointRef.getMethod(),
                    testSuiteId,
                    jsonbMapper.mapResponseColumns(suite.getResponseColumns()));
        }

        // Multi-request suite: run the chain with every request single-turn and the converted variables
        // wholesale-replacing each request's own inputBindings (design D8). The additionalRequests list
        // mapped above is reused, so the JSONB is deserialized once per try-out.
        final ResolvedRequestService.ChainPlan plan =
                resolvedRequestService.planChainForVariables(testSuiteId, additionalRequests);
        return runChain(plan, deploymentRef, testSuiteId, bindings);
    }

    private TestSuite loadSuite(UUID testSuiteId) {
        return testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
    }

    /**
     * Validates the whole chain's preconditions before anything is planned or invoked (design D7):
     * {@code deploymentRef} once (suite-level), request #0 via the pre-existing suite-level checks (typed
     * {@code endpointRef} + method, raw-JSON blank check on the template), then each chain element's own
     * typed {@code endpointRef}/method and {@code requestTemplate} — per-element failures name the element
     * with the {@code additionalRequests[i]} prefix, matching the write-time validator's convention. A
     * misconfigured element therefore returns 400 with zero HTTP calls issued.
     */
    private void validateChainPreconditions(
            DeploymentReferenceDto deploymentRef,
            EndpointContractDto endpointRef,
            String rawTemplateJson,
            List<RequestDefinitionDto> additionalRequests) {
        validateSuitePreconditions(deploymentRef, endpointRef, rawTemplateJson);
        if (additionalRequests == null) {
            return;
        }
        for (int i = 0; i < additionalRequests.size(); i++) {
            RequestDefinitionDto element = additionalRequests.get(i);
            if (element.getEndpointRef() == null || element.getEndpointRef().getMethod() == null) {
                throw new ValidationException("additionalRequests[" + i
                        + "]: endpoint reference with HTTP method is required for try-it-out");
            }
            if (element.getRequestTemplate() == null) {
                throw new ValidationException(
                        "additionalRequests[" + i + "]: request template is required for try-it-out");
            }
        }
    }

    private void validateSuitePreconditions(
            DeploymentReferenceDto deploymentRef, EndpointContractDto endpointRef, String requestTemplateJson) {
        if (deploymentRef == null) {
            throw new ValidationException("Deployment reference is required for try-it-out");
        }
        if (endpointRef == null || endpointRef.getMethod() == null) {
            throw new ValidationException("Endpoint reference with HTTP method is required for try-it-out");
        }
        if (requestTemplateJson == null || requestTemplateJson.isBlank()) {
            throw new ValidationException("Request template is required for try-it-out");
        }
    }

    private void validateResolutionResult(ResolvedRequestDto resolved) {
        if (resolved.getUrl() == null) {
            throw new ValidationException("Resolved URL is required for invocation");
        }
        final List<ValidationWarningDto> warnings = resolved.getWarnings() != null ? resolved.getWarnings() : List.of();

        final List<ValidationWarningDto> requiredWarnings = warnings.stream()
                .filter(w -> w.getCode() == ValidationWarningCode.REQUIRED)
                .toList();
        if (!requiredWarnings.isEmpty()) {
            final String unresolvedVars = requiredWarnings.stream()
                    .map(ValidationWarningDto::getFieldName)
                    .collect(Collectors.joining(", "));
            throw new TryItOutValidationException(
                    "Unresolved required template variables: [" + unresolvedVars + "]", resolved);
        }

        final boolean hasBodyEvaluationError =
                warnings.stream().anyMatch(w -> w.getCode() == ValidationWarningCode.REQUEST_BODY_EVALUATION_ERROR);
        if (hasBodyEvaluationError) {
            throw new TryItOutValidationException("Request body template failed to evaluate", resolved);
        }
    }

    private List<InputBindingDto> convertVariablesToBindings(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return List.of();
        }
        List<InputBindingDto> bindings = new ArrayList<>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            bindings.add(InputBindingDto.builder()
                    .templateVariable(entry.getKey())
                    .constantValue(entry.getValue())
                    .build());
        }
        return bindings;
    }

    private boolean isMcpSuite(TestSuite suite) {
        return suite.getSuiteType() == SuiteType.MCP_TOOL;
    }

    private void validateMcpResolutionResult(McpRequestResolver.ResolutionResult result) {
        List<ValidationWarningDto> requiredWarnings = result.getWarnings() != null
                ? result.getWarnings().stream()
                        .filter(w -> w.getCode() == ValidationWarningCode.REQUIRED)
                        .toList()
                : List.of();
        if (!requiredWarnings.isEmpty()) {
            String unresolvedVars = requiredWarnings.stream()
                    .map(ValidationWarningDto::getFieldName)
                    .collect(Collectors.joining(", "));
            ResolvedRequestDto resolvedRequest = ResolvedRequestDto.builder()
                    .body(ResolvedJsonBodyDto.builder()
                            .content(result.getArguments())
                            .build())
                    .warnings(result.getWarnings())
                    .build();
            throw new TryItOutValidationException(
                    "Unresolved required template variables: [" + unresolvedVars + "]", resolvedRequest);
        }
    }

    private void validateMcpPreconditions(McpDeploymentReferenceDto mcpRef, ToolReferenceDto toolRef) {
        if (mcpRef == null) {
            throw new ValidationException("MCP deployment reference is required for try-it-out");
        }
        if (toolRef == null) {
            throw new ValidationException("Tool reference is required for try-it-out");
        }
    }

    private Map<String, Object> parseTestCaseData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(dataJson, MAP_TYPE_REF);
        } catch (JacksonException e) {
            throw new ValidationException("Failed to parse test case data: " + e.getMessage());
        }
    }

    private TryItOutResponseDto invokeMcpAndBuildResponse(
            McpDeploymentReferenceDto mcpRef,
            ToolReferenceDto toolRef,
            Map<String, Object> resolvedArgs,
            UUID testSuiteId) {
        Span span = openTelemetry
                .getTracer(TracingConstants.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder(TracingConstants.SPAN_TRY_IT_OUT_MCP_INVOKE)
                .setAttribute(TracingConstants.EVAL_SUITE_ID, testSuiteId.toString())
                .setAttribute(TracingConstants.MCP_TOOL_NAME, toolRef.getName())
                .startSpan();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        try (Scope scope = span.makeCurrent()) {
            String token = AuthorizationTokenHolder.getToken();
            McpTransport transport =
                    mcpRef.getTransport() != null ? mcpRef.getTransport() : McpTransport.STREAMABLE_HTTP;
            long startMs = clock.millis();
            CallToolResult result =
                    mcpToolInvoker.callTool(mcpRef.getId(), toolRef.getName(), resolvedArgs, token, transport);
            long durationMs = clock.millis() - startMs;

            String serializedResponse = mcpResponseSerializer.serialize(result);

            TryItOutCoreResponseDto responseDto = TryItOutCoreResponseDto.builder()
                    .statusCode(200)
                    .body(objectMapper.readTree(serializedResponse))
                    .build();

            return TryItOutResponseDto.builder()
                    .resolvedRequest(ResolvedRequestDto.builder()
                            .body(ResolvedJsonBodyDto.builder()
                                    .content(resolvedArgs)
                                    .build())
                            .build())
                    .response(responseDto)
                    .durationMs(durationMs)
                    .traceId(traceId)
                    .grafanaTraceUrl(grafanaLinkBuilder.traceUrl(traceId))
                    .build();
        } catch (McpInvocationException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } catch (JacksonException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException("Failed to serialize MCP response", e);
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Single-invocation fast path (HTTP only — the MCP path never extracts): invokes once and, when the
     * suite defines response columns and the invocation succeeded, adds the additive {@code
     * extractedColumns}/{@code extractionWarnings} fields. With no response columns the extractor is never
     * called and both fields stay omitted, keeping such suites' responses byte-identical (design D6).
     */
    private TryItOutResponseDto invokeAndBuildResponse(
            ResolvedRequestDto resolved,
            DeploymentReferenceDto deploymentRef,
            HttpMethod method,
            UUID testSuiteId,
            List<ResponseColumnDefinitionDto> responseColumns) {
        final TurnInvocationResult result = invokeTurn(resolved, deploymentRef, method, testSuiteId);
        ResponseColumnExtractor.ExtractionResult extraction = null;
        if (responseColumns != null && !responseColumns.isEmpty() && result.status() == ExecutionStatus.SUCCESS) {
            extraction = extractColumns(responseColumns, result);
        }
        return TryItOutResponseDto.builder()
                .resolvedRequest(result.resolvedRequest())
                .response(result.response())
                .durationMs(result.durationMs())
                .traceId(result.traceId())
                .grafanaTraceUrl(grafanaLinkBuilder.traceUrl(result.traceId()))
                .extractedColumns(extraction != null ? objectMapper.readTree(extraction.extractedColumns()) : null)
                .extractionWarnings(extraction != null ? objectMapper.readTree(extraction.extractionWarnings()) : null)
                .build();
    }

    private TurnInvocationResult invokeTurn(
            ResolvedRequestDto resolved, DeploymentReferenceDto deploymentRef, HttpMethod method, UUID testSuiteId) {
        String fullPath = urlBuilder.buildUrl(deploymentRef.getId(), resolved.getUrl());
        HttpHeaders headers = toHttpHeaders(resolved.getHeaders());
        MultiValueMap<String, String> queryParams = toQueryParams(resolved.getQueryParams());

        // Serialize body via content-type-aware registry
        ResolvedBodyDto resolvedBody = resolved.getBody();
        Object serializedBody = null;
        if (resolvedBody != null) {
            SerializedBody serialized = serializerRegistry.serialize(resolvedBody);
            // Skip setting Content-Type for multipart — RestClient auto-generates boundary
            if (!MediaType.MULTIPART_FORM_DATA.equals(serialized.contentType())) {
                headers.setContentType(serialized.contentType());
            }
            serializedBody = serialized.body();
        }

        Span span = openTelemetry
                .getTracer(TracingConstants.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder(TracingConstants.SPAN_TRY_IT_OUT_INVOKE)
                .setAttribute(TracingConstants.EVAL_SUITE_ID, testSuiteId.toString())
                .startSpan();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        try (Scope scope = span.makeCurrent()) {
            long startMs = clock.millis();
            try (DeploymentInvocationResult result =
                    deploymentInvoker.invokeWithStreaming(method, fullPath, headers, queryParams, serializedBody)) {
                final long durationMs = clock.millis() - startMs;
                ExecutionStatus status = DeploymentInvocationSupport.resolveExecutionStatus(result.statusCode());
                final TryItOutCoreResponseDto responseDto;
                final String extractionDocumentJson;

                if (result.streaming()) {
                    final StreamingInvocation streaming = readStream(result);
                    responseDto = streaming.response();
                    extractionDocumentJson = streaming.extractionDocumentJson();
                    if (streaming.status() != ExecutionStatus.SUCCESS) {
                        status = streaming.status();
                    }
                } else {
                    responseDto = TryItOutCoreResponseDto.builder()
                            .statusCode(result.statusCode())
                            .body(result.body())
                            .build();
                    extractionDocumentJson = null;
                }

                return new TurnInvocationResult(
                        resolved, responseDto, durationMs, traceId, status, extractionDocumentJson);
            }
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw new RuntimeException("Failed to close streaming result", e);
        } finally {
            span.end();
        }
    }

    /**
     * Consumes a streaming response exactly once and produces both views of it: the display view (the
     * {@code {"events":[…]}} envelope plus the verbatim event list the UI renders — an unchanged contract)
     * and the extraction view (the run path's assembled document, so response-column JSONata sees what a
     * real run would). The parse happens here and its result is handed to {@link
     * StreamingResponseAccumulator#assemble(SseParseResult)} — the same accumulator {@code
     * DeploymentTurnInvoker} uses — instead of re-reading the already-drained stream. A non-{@code
     * SUCCESS} parse status is reported back so the caller treats the invocation as failed.
     */
    private StreamingInvocation readStream(DeploymentInvocationResult result) {
        final long maxBytes = evaluationRunProperties.getExecution().getMaxResponseSizeBytes();
        // Idle timeout = per-path read timeout; absolute cap = global property.
        final long idleTimeoutMs = dialCoreProperties.getTryOut().getReadTimeoutMs();
        final long maxTotalDurationMs = sseEventProcessingProperties.getMaxTotalDurationMs();
        final SseParseResult parseResult =
                sseEventParser.parse(result.eventStream(), idleTimeoutMs, maxTotalDurationMs, maxBytes);

        final StreamingResponseAccumulator accumulator = new StreamingResponseAccumulator(
                sseEventParser, objectMapper, idleTimeoutMs, maxTotalDurationMs, maxBytes);
        accumulator.assemble(parseResult);

        final List<SseEventDto> eventDtos = parseResult.events().stream()
                .map(e -> SseEventDto.builder().event(e.event()).data(e.data()).build())
                .toList();

        final ExecutionStatus status = accumulator.getExecutionStatus();
        final TryItOutCoreResponseDto responseDto = TryItOutCoreResponseDto.builder()
                .statusCode(result.statusCode())
                .body(buildSseEnvelope(parseResult.events()))
                .streaming(true)
                .events(eventDtos)
                .streamingStatus(status != ExecutionStatus.SUCCESS ? status : null)
                .truncationWarning(accumulator.getTruncationWarning())
                .build();
        return new StreamingInvocation(responseDto, status, accumulator.getResponseBody());
    }

    /**
     * One streaming invocation's two views: the response DTO shown to the client (events envelope + event
     * list) and the run-equivalent assembled document used for response-column extraction, plus the
     * stream's terminal status.
     */
    private record StreamingInvocation(
            TryItOutCoreResponseDto response, ExecutionStatus status, String extractionDocumentJson) {}

    /**
     * The display envelope: every received event in order, regardless of the stream's mode. It is what the
     * response DTO's {@code body} carries (and what the accumulator's structured-SSE mode also produces);
     * extraction, however, runs against {@link StreamingInvocation#extractionDocumentJson()}.
     */
    private ObjectNode buildSseEnvelope(List<SseEvent> events) {
        ObjectNode envelope = objectMapper.createObjectNode();
        ArrayNode eventsArray = objectMapper.createArrayNode();
        for (SseEvent event : events) {
            ObjectNode eventNode = objectMapper.createObjectNode();
            eventNode.put("event", event.event());
            if (event.data() instanceof JsonNode jsonNode) {
                eventNode.set("data", jsonNode);
            } else {
                eventNode.put("data", (String) event.data());
            }
            eventsArray.add(eventNode);
        }
        envelope.set("events", eventsArray);
        return envelope;
    }

    private static HttpHeaders toHttpHeaders(List<KeyValueTemplateDto> headerList) {
        HttpHeaders headers = new HttpHeaders();
        if (headerList != null) {
            for (KeyValueTemplateDto kv : headerList) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    headers.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return headers;
    }

    private static MultiValueMap<String, String> toQueryParams(List<KeyValueTemplateDto> paramList) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (paramList != null) {
            for (KeyValueTemplateDto kv : paramList) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    params.add(kv.getKey(), kv.getValue());
                }
            }
        }
        return params;
    }

    /**
     * Validation exception that carries the resolved request for inclusion in error details.
     */
    public static class TryItOutValidationException extends ValidationException {
        private final ResolvedRequestDto resolvedRequest;

        public TryItOutValidationException(String message, ResolvedRequestDto resolvedRequest) {
            super(message);
            this.resolvedRequest = resolvedRequest;
        }

        public ResolvedRequestDto getResolvedRequest() {
            return resolvedRequest;
        }
    }
}
