package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.runner.service.McpRequestResolver;
import com.epam.aidial.evaluation.runner.service.McpResponseSerializer;
import com.epam.aidial.evaluation.runner.service.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.service.SerializedBody;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EvaluationWorker")
@ExtendWith(MockitoExtension.class)
class EvaluationWorkerTest {

    @Mock
    private RequestResolver requestResolver;

    @Mock
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Mock
    private DialCoreUrlBuilder urlBuilder;

    @Mock
    private RequestBodySerializerRegistry serializerRegistry;

    @Mock
    private ResponseColumnExtractor responseColumnExtractor;

    @Mock
    private RunnerJsonbMapper jsonbMapper;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution execution;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OpenTelemetry openTelemetry;

    @Mock
    private McpToolInvoker mcpToolInvoker;

    @Mock
    private McpRequestResolver mcpRequestResolver;

    @Mock
    private McpResponseSerializer mcpResponseSerializer;

    @Mock
    private MultiTurnExecutor multiTurnExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    private EvaluationWorker worker;

    @BeforeEach
    void setUp() {
        SseEventParser sseEventParser = new SseEventParser(objectMapper, FIXED_CLOCK);
        SseEventProcessingProperties sseEventProcessingProperties = new SseEventProcessingProperties();
        sseEventProcessingProperties.setMaxTotalDurationMs(3_600_000L);
        worker = new EvaluationWorker(
                requestResolver,
                deploymentInvoker,
                urlBuilder,
                serializerRegistry,
                responseColumnExtractor,
                objectMapper,
                jsonbMapper,
                evaluationRunProperties,
                openTelemetry,
                mcpToolInvoker,
                mcpRequestResolver,
                mcpResponseSerializer,
                FIXED_CLOCK,
                sseEventParser,
                sseEventProcessingProperties,
                multiTurnExecutor);
    }

    @Test
    @DisplayName("Should return SUCCESS result for non-streaming 200 response")
    void execute_nonStreamingSuccess_returnsSuccessResult() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        Map<String, Object> responseBody = Map.of("choices", List.of());
        DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, false, responseBody, null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getResponseBody()).isNotNull();
        assertThat(result.getExecDurationMs()).isNotNull();
        assertThat(result.getTestSuiteRunId()).isEqualTo(context.getRunId());
        assertThat(result.getTestCaseId()).isEqualTo(input.getTestCaseId());
        assertThat(result.getTestSuiteId()).isEqualTo(context.getSuiteId());
        assertThat(result.getCreatedAtMs()).isEqualTo(context.getCreatedAtMs());
        assertThat(result.getRetryCount()).isEqualTo(0);
        assertThat(result.getLogDetails()).isNull();
        assertThat(result.getRequestBody()).isNotNull();
    }

    @Test
    @DisplayName("Should return FAILED result for HTTP 500 response")
    void execute_httpError_returnsFailedResult() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        DeploymentInvocationResult invocationResult = new DeploymentInvocationResult(
                500, false, Map.of("error", "Internal Server Error"), null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getResponseStatusCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("Should return ERROR result for HTTP 401 authentication error")
    void execute_authError_returnsErrorResult() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(401, false, Map.of("error", "Unauthorized"), null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getResponseStatusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Should return TIMEOUT result when HttpTimeoutException is thrown")
    void execute_timeout_returnsTimeoutResult() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenThrow(new RuntimeException(new HttpTimeoutException("Request timed out")));

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(result.getResponseStatusCode()).isNull();
        assertThat(result.getResponseBody()).contains("INVOCATION_ERROR");
    }

    @Test
    @DisplayName("Should return ERROR result when IOException is thrown")
    void execute_networkError_returnsErrorResult() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenThrow(new RuntimeException(new IOException("Connection refused")));

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getResponseStatusCode()).isNull();
        assertThat(result.getResponseBody()).contains("INVOCATION_ERROR");
    }

    @Test
    @DisplayName("Should return ERROR result and truncate response when body exceeds maxResponseSizeBytes")
    void execute_responseTruncation_truncatesAndSetsError() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContextWithMaxResponseSize(50L);
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        String largeBody = "A".repeat(200);
        DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, false, largeBody, null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should retry on 500 server error and succeed on second attempt with retry tracking")
    void execute_retryOnServerError_retriesAndSucceeds() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContextWithRetries(1);
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        DeploymentInvocationResult failResult =
                new DeploymentInvocationResult(500, false, Map.of("error", "Server Error"), null, new HttpHeaders());
        DeploymentInvocationResult successResult =
                new DeploymentInvocationResult(200, false, Map.of("choices", List.of()), null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(failResult)
                .thenReturn(successResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getLogDetails()).isNotNull();
        assertThat(result.getLogDetails()).contains("retryAttempts");
        assertThat(result.getRequestBody()).isNotNull();

        verify(deploymentInvoker, times(2))
                .invokeWithStreaming(any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any());
    }

    @Test
    @DisplayName("Should not retry on 400 client error even with maxRetries=2")
    void execute_noRetryOn4xx_doesNotRetry() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContextWithRetries(2);
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();

        DeploymentInvocationResult clientErrorResult =
                new DeploymentInvocationResult(400, false, Map.of("error", "Bad Request"), null, new HttpHeaders());

        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(clientErrorResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getResponseStatusCode()).isEqualTo(400);
        assertThat(result.getRetryCount()).isEqualTo(0);
        assertThat(result.getLogDetails()).isNull();

        verify(deploymentInvoker, times(1))
                .invokeWithStreaming(any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any());
    }

    @Test
    @DisplayName(
            "Should set traceId on result from span context (span has 6 attributes: testcase.id, testcase.name, run.index, eval.run.id, eval.suite.id, eval.phase)")
    void execute_setsTraceIdFromSpanContext() throws Exception {
        // given — mock chain models 6 setAttribute calls matching the production code:
        // .setAttribute("testcase.id", ...).setAttribute("testcase.name", ...)
        // .setAttribute("run.index", ...).setAttribute("eval.run.id", ...)
        // .setAttribute("eval.suite.id", ...).setAttribute("eval.phase", ...)
        String expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        when(openTelemetry
                        .getTracer(anyString())
                        .spanBuilder(anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .startSpan()
                        .getSpanContext()
                        .isValid())
                .thenReturn(true);
        when(openTelemetry
                        .getTracer(anyString())
                        .spanBuilder(anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .startSpan()
                        .getSpanContext()
                        .getTraceId())
                .thenReturn(expectedTraceId);

        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        stubCommonMocks();
        DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, false, Map.of("choices", List.of()), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(any(), anyString(), any(), any(), any()))
                .thenReturn(invocationResult);
        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        // then
        assertThat(result.getTraceId()).isEqualTo(expectedTraceId);
    }

    @Test
    @DisplayName("Should skip blacklisted headers (Authorization, Host) when building request")
    void execute_headerBlacklistFiltering_skipsBlacklistedHeaders() throws Exception {
        // given
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        ResolvedRequestDto resolvedRequest = ResolvedRequestDto.builder()
                .url("/chat/completions")
                .headers(List.of(
                        KeyValueTemplateDto.builder()
                                .key("Authorization")
                                .value("Bearer secret")
                                .build(),
                        KeyValueTemplateDto.builder()
                                .key("Host")
                                .value("evil.com")
                                .build(),
                        KeyValueTemplateDto.builder()
                                .key("X-Custom")
                                .value("allowed-value")
                                .build()))
                .queryParams(List.of())
                .body(ResolvedJsonBodyDto.builder()
                        .content(Map.of("messages", List.of(Map.of("role", "user", "content", "hello"))))
                        .build())
                .build();

        when(requestResolver.resolve(any(), any(), any())).thenReturn(resolvedRequest);
        when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                .thenReturn("/openai/deployments/gpt-4/chat/completions");
        when(evaluationRunProperties.getExecution()).thenReturn(execution);
        when(execution.getHeaderBlacklist()).thenReturn(List.of("Authorization", "Host"));
        when(serializerRegistry.serialize(any(ResolvedBodyDto.class)))
                .thenReturn(new SerializedBody(MediaType.APPLICATION_JSON, Map.of()));

        DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, false, Map.of("choices", List.of()), null, new HttpHeaders());

        ArgumentCaptor<HttpHeaders> headersCaptor = ArgumentCaptor.forClass(HttpHeaders.class);
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), headersCaptor.capture(), any(), any()))
                .thenReturn(invocationResult);

        when(responseColumnExtractor.extract(anyList(), anyString()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        // when
        worker.execute(input, context, 0, responseColumns);

        // then
        HttpHeaders capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.containsHeader("Authorization")).isFalse();
        assertThat(capturedHeaders.containsHeader("Host")).isFalse();
        assertThat(capturedHeaders.getFirst("X-Custom")).isEqualTo("allowed-value");
        assertThat(capturedHeaders.containsHeader("X-Correlation-Id")).isFalse();
    }

    // ------------------------------------------------------------------
    // MCP execution path tests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("MCP execution path")
    class McpExecutionPath {

        @Test
        @DisplayName("Should return SUCCESS for successful MCP tool call")
        void execute_mcpSuccess_returnsSuccessResult() throws Exception {
            TestCaseRunInput input = buildTestCaseRunInput();
            EvaluationContext context = buildMcpContext();

            CallToolResult callResult = new CallToolResult(
                    List.of(TextContent.builder("result text").build()), false, null, null);

            when(mcpRequestResolver.resolve(any(), any(), any()))
                    .thenReturn(McpRequestResolver.ResolutionResult.builder()
                            .arguments(Map.of("query", "test"))
                            .warnings(List.of())
                            .build());
            when(mcpToolInvoker.callTool(eq("my-toolset"), eq("search"), any(), eq("test-token"), any()))
                    .thenReturn(callResult);
            when(mcpResponseSerializer.serialize(callResult))
                    .thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"result text\"}]}");
            when(responseColumnExtractor.extract(anyList(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(result.getResponseBody()).contains("result text");
            assertThat(result.getResponseStatusCode()).isNull();
            assertThat(result.getRequestBody()).isNotNull();
        }

        @Test
        @DisplayName("Should return FAILED when MCP tool returns isError=true")
        void execute_mcpIsError_returnsFailedResult() throws Exception {
            TestCaseRunInput input = buildTestCaseRunInput();
            EvaluationContext context = buildMcpContext();

            CallToolResult errorResult =
                    new CallToolResult(List.of(TextContent.builder("tool error").build()), true, null, null);

            when(mcpRequestResolver.resolve(any(), any(), any()))
                    .thenReturn(McpRequestResolver.ResolutionResult.builder()
                            .arguments(Map.of("query", "bad"))
                            .warnings(List.of())
                            .build());
            when(mcpToolInvoker.callTool(any(), any(), any(), any(), any())).thenReturn(errorResult);
            when(mcpResponseSerializer.serialize(errorResult))
                    .thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"tool error\"}],\"isError\":true}");
            when(responseColumnExtractor.extract(anyList(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        }

        @Test
        @DisplayName("Should return TIMEOUT when MCP invocation throws 504 McpInvocationException")
        void execute_mcpTimeout_returnsTimeoutResult() throws Exception {
            TestCaseRunInput input = buildTestCaseRunInput();
            EvaluationContext context = buildMcpContext();

            when(mcpRequestResolver.resolve(any(), any(), any()))
                    .thenReturn(McpRequestResolver.ResolutionResult.builder()
                            .arguments(Map.of("query", "test"))
                            .warnings(List.of())
                            .build());
            when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                    .thenThrow(new McpInvocationException(504, "MCP_TIMEOUT", "MCP tool invocation timed out"));
            when(responseColumnExtractor.extract(anyList(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
            assertThat(result.getResponseBody()).contains("MCP_INVOCATION_ERROR");
        }

        @Test
        @DisplayName("Should return ERROR when MCP invocation throws 502 McpInvocationException")
        void execute_mcpConnectionError_returnsErrorResult() throws Exception {
            TestCaseRunInput input = buildTestCaseRunInput();
            EvaluationContext context = buildMcpContext();

            when(mcpRequestResolver.resolve(any(), any(), any()))
                    .thenReturn(McpRequestResolver.ResolutionResult.builder()
                            .arguments(Map.of("query", "test"))
                            .warnings(List.of())
                            .build());
            when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                    .thenThrow(new McpInvocationException(
                            502, "MCP_CONNECTION_ERROR", "Failed to connect to MCP endpoint"));
            when(responseColumnExtractor.extract(anyList(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(result.getResponseBody()).contains("MCP_INVOCATION_ERROR");
        }

        @Test
        @DisplayName("Should return ERROR when MCP response serialization fails")
        void execute_mcpSerializationFails_returnsErrorResult() throws Exception {
            TestCaseRunInput input = buildTestCaseRunInput();
            EvaluationContext context = buildMcpContext();

            CallToolResult callResult =
                    new CallToolResult(List.of(TextContent.builder("result").build()), false, null, null);

            when(mcpRequestResolver.resolve(any(), any(), any()))
                    .thenReturn(McpRequestResolver.ResolutionResult.builder()
                            .arguments(Map.of("query", "test"))
                            .warnings(List.of())
                            .build());
            when(mcpToolInvoker.callTool(any(), any(), any(), any(), any())).thenReturn(callResult);
            when(mcpResponseSerializer.serialize(callResult)).thenThrow(new JacksonException("serialization error") {});
            when(responseColumnExtractor.extract(anyList(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(result.getResponseBody()).contains("MCP_INVOCATION_ERROR");
        }
    }

    // ------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------

    private void stubCommonMocks() {
        ResolvedRequestDto resolvedRequest = buildResolvedRequest();
        when(requestResolver.resolve(any(), any(), any())).thenReturn(resolvedRequest);
        when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                .thenReturn("/openai/deployments/gpt-4/chat/completions");
        when(evaluationRunProperties.getExecution()).thenReturn(execution);
        when(execution.getHeaderBlacklist()).thenReturn(List.of("Authorization", "Host"));
        when(serializerRegistry.serialize(any(ResolvedBodyDto.class)))
                .thenReturn(new SerializedBody(MediaType.APPLICATION_JSON, Map.of()));
    }

    private EvaluationContext buildContext() {
        return buildContextBase().build();
    }

    private EvaluationContext buildContextWithMaxResponseSize(long maxResponseSizeBytes) {
        return buildContextBase().maxResponseSizeBytes(maxResponseSizeBytes).build();
    }

    private EvaluationContext buildContextWithRetries(int maxRetries) {
        return buildContextBase()
                .maxRetries(maxRetries)
                .retryBackoffMultiplier(1.0)
                .build();
    }

    private EvaluationContext.EvaluationContextBuilder buildContextBase() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .concurrencyLevel(1)
                .requestTimeoutMs(30000L)
                .maxRetries(0)
                .retryDelayMs(100L)
                .retryBackoffMultiplier(2.0)
                .maxRetryDelayMs(1000L)
                .resultBatchSize(100)
                .maxResponseSizeBytes(5242880L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("test-token")
                .createdAtMs(System.currentTimeMillis())
                .snapshotDeploymentRef(DeploymentReferenceDto.builder()
                        .id("gpt-4")
                        .name("GPT-4")
                        .build())
                .snapshotEndpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/chat/completions")
                        .build())
                .snapshotRequestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/chat/completions")
                        .build())
                .snapshotInputBindings(List.of())
                .snapshotResponseColumns(List.of());
    }

    private TestCaseRunInput buildTestCaseRunInput() {
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .position(0)
                .testCaseId(UUID.randomUUID())
                .testCaseName("test-case-1")
                .testCaseData("{}")
                .build();
    }

    private ResolvedRequestDto buildResolvedRequest() {
        return ResolvedRequestDto.builder()
                .url("/chat/completions")
                .headers(List.of())
                .queryParams(List.of())
                .body(ResolvedJsonBodyDto.builder()
                        .content(Map.of("messages", List.of(Map.of("role", "user", "content", "hello"))))
                        .build())
                .build();
    }

    private EvaluationContext buildMcpContext() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .concurrencyLevel(1)
                .requestTimeoutMs(30000L)
                .maxRetries(0)
                .retryDelayMs(100L)
                .retryBackoffMultiplier(2.0)
                .maxRetryDelayMs(1000L)
                .resultBatchSize(100)
                .maxResponseSizeBytes(5242880L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .token("test-token")
                .createdAtMs(FIXED_CLOCK.millis())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRefDto(McpDeploymentReferenceDto.builder()
                        .id("my-toolset")
                        .type("dial-toolset")
                        .build())
                .toolRefDto(ToolReferenceDto.builder()
                        .name("search")
                        .inputSchema(Map.of("type", "object"))
                        .build())
                .argumentTemplateDto(ArgumentTemplateDto.builder()
                        .arguments(Map.of("query", "${{userQuery}}"))
                        .build())
                .inputBindings(List.of())
                .build();
    }
}
