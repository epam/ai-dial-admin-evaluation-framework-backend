package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.client.mcp.McpTransport;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.configuration.security.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.constants.TracingConstants;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.SseEventDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutCoreResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.SseEvent;
import com.epam.aidial.evaluation.service.domain.job.SseEventParser;
import com.epam.aidial.evaluation.service.domain.job.SseParseResult;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.time.Clock;
import java.util.ArrayList;
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

            // Suite-level bindings are the effective bindings — per-test-case overrides no longer exist
            List<InputBindingDto> effectiveBindings = jsonbMapper.mapInputBindings(suite.getInputBindings());

            Map<String, Object> testCaseData = parseTestCaseData(testCase.getData());
            McpRequestResolver.ResolutionResult resolutionResult =
                    mcpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData);
            validateMcpResolutionResult(resolutionResult);
            return invokeMcpAndBuildResponse(mcpRef, toolRef, resolutionResult.getArguments(), testSuiteId);
        }

        DeploymentReferenceDto deploymentRef = jsonbMapper.map(suite.getDeploymentRef());
        EndpointContractDto endpointRef = jsonbMapper.mapEndpointContract(suite.getEndpointRef());
        validateSuitePreconditions(deploymentRef, endpointRef, suite.getRequestTemplate());

        ResolvedRequestDto resolved = resolvedRequestService.resolveRequest(testSuiteId, testCaseId);
        validateResolutionResult(resolved);

        return invokeAndBuildResponse(resolved, deploymentRef, endpointRef.getMethod(), testSuiteId);
    }

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
        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        validateSuitePreconditions(deploymentRef, endpointRef, suite.getRequestTemplate());

        List<InputBindingDto> bindings = convertVariablesToBindings(variables);
        ResolvedRequestDto resolved = resolvedRequestService.resolve(template, bindings, Map.of());
        validateResolutionResult(resolved);

        return invokeAndBuildResponse(resolved, deploymentRef, endpointRef.getMethod(), testSuiteId);
    }

    private TestSuite loadSuite(UUID testSuiteId) {
        return testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
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
        List<ValidationWarningDto> requiredWarnings = resolved.getWarnings() != null
                ? resolved.getWarnings().stream()
                        .filter(w -> w.getCode() == ValidationWarningCode.REQUIRED)
                        .toList()
                : List.of();
        if (!requiredWarnings.isEmpty()) {
            String unresolvedVars = requiredWarnings.stream()
                    .map(ValidationWarningDto::getFieldName)
                    .collect(Collectors.joining(", "));
            throw new TryItOutValidationException(
                    "Unresolved required template variables: [" + unresolvedVars + "]", resolved);
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
                .getTracer("com.epam.aidial.evaluation")
                .spanBuilder("try-it-out.mcp.invoke")
                .setAttribute(TracingConstants.EVAL_SUITE_ID, testSuiteId.toString())
                .setAttribute("mcp.tool.name", toolRef.getName())
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

    private TryItOutResponseDto invokeAndBuildResponse(
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
                .getTracer("com.epam.aidial.evaluation")
                .spanBuilder("try-it-out.invoke")
                .setAttribute(TracingConstants.EVAL_SUITE_ID, testSuiteId.toString())
                .startSpan();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        try (Scope scope = span.makeCurrent()) {
            long startMs = clock.millis();
            try (DeploymentInvocationResult result =
                    deploymentInvoker.invokeWithStreaming(method, fullPath, headers, queryParams, serializedBody)) {
                long durationMs = clock.millis() - startMs;
                TryItOutCoreResponseDto responseDto;

                if (result.streaming()) {
                    responseDto = buildSseResponse(result);
                } else {
                    responseDto = TryItOutCoreResponseDto.builder()
                            .statusCode(result.statusCode())
                            .body(result.body())
                            .build();
                }

                return TryItOutResponseDto.builder()
                        .resolvedRequest(resolved)
                        .response(responseDto)
                        .durationMs(durationMs)
                        .traceId(traceId)
                        .grafanaTraceUrl(grafanaLinkBuilder.traceUrl(traceId))
                        .build();
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

    private TryItOutCoreResponseDto buildSseResponse(DeploymentInvocationResult result) {
        long maxBytes = evaluationRunProperties.getExecution().getMaxResponseSizeBytes();
        // Idle timeout = per-path read timeout; absolute cap = global property.
        SseParseResult parseResult = sseEventParser.parse(
                result.eventStream(),
                dialCoreProperties.getTryOut().getReadTimeoutMs(),
                sseEventProcessingProperties.getMaxTotalDurationMs(),
                maxBytes);

        List<SseEventDto> eventDtos = parseResult.events().stream()
                .map(e -> SseEventDto.builder().event(e.event()).data(e.data()).build())
                .toList();

        ObjectNode envelope = buildSseEnvelope(parseResult.events());

        return TryItOutCoreResponseDto.builder()
                .statusCode(result.statusCode())
                .body(envelope)
                .streaming(true)
                .events(eventDtos)
                .build();
    }

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
