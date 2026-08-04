package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
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
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Every DEPLOYMENT HTTP test case (single-turn and multi-turn alike) is delegated whole to
 * {@link TurnLoopExecutor}; {@link EvaluationWorker} itself only decides MCP-vs-HTTP dispatch, builds the
 * tracing span/baggage, and owns the MCP execution path end to end. HTTP-path behavior (retries, timeouts,
 * header filtering, turn-loop semantics) is covered by {@link TurnLoopExecutorTest}.
 */
@DisplayName("EvaluationWorker")
@ExtendWith(MockitoExtension.class)
class EvaluationWorkerTest {

    @Mock
    private ResponseColumnExtractor responseColumnExtractor;

    @Mock
    private RunnerJsonbMapper jsonbMapper;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OpenTelemetry openTelemetry;

    @Mock
    private McpToolInvoker mcpToolInvoker;

    @Mock
    private McpRequestResolver mcpRequestResolver;

    @Mock
    private McpResponseSerializer mcpResponseSerializer;

    @Mock
    private TurnLoopExecutor turnLoopExecutor;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Span span;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    private EvaluationWorker worker;

    @BeforeEach
    void setUp() {
        worker = new EvaluationWorker(
                responseColumnExtractor,
                objectMapper,
                jsonbMapper,
                openTelemetry,
                mcpToolInvoker,
                mcpRequestResolver,
                mcpResponseSerializer,
                FIXED_CLOCK,
                turnLoopExecutor);
    }

    @Test
    @DisplayName("Should delegate every DEPLOYMENT case to TurnLoopExecutor and return its rows unchanged")
    void execute_deploymentSuite_delegatesToTurnLoopExecutor() {
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        TestCaseRunResult stubbedRow = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();
        // traceId is null here (the mocked OpenTelemetry span isn't stubbed valid) — match with any(),
        // not anyString(), since anyString() rejects null and would misroute this call to the outer
        // catch block instead of the stub.
        when(turnLoopExecutor.execute(eq(input), eq(context), eq(0), eq(responseColumns), any(), anyLong()))
                .thenReturn(List.of(stubbedRow));

        List<TestCaseRunResult> results = worker.execute(input, context, 0, responseColumns);

        assertThat(results).containsExactly(stubbedRow);
    }

    @Test
    @DisplayName("Should pass the span's traceId through to TurnLoopExecutor")
    void execute_deploymentSuite_passesTraceIdToTurnLoopExecutor() {
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
        when(turnLoopExecutor.execute(any(), any(), eq(0), anyList(), anyString(), anyLong()))
                .thenReturn(List.of());

        worker.execute(input, context, 0, responseColumns);

        ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(turnLoopExecutor)
                .execute(eq(input), eq(context), eq(0), eq(responseColumns), traceIdCaptor.capture(), anyLong());
        assertThat(traceIdCaptor.getValue()).isEqualTo(expectedTraceId);
    }

    @Test
    @DisplayName("Should return an ERROR result when TurnLoopExecutor throws unexpectedly")
    void execute_turnLoopExecutorThrows_returnsErrorResult() {
        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();

        when(turnLoopExecutor.execute(any(), any(), eq(0), anyList(), any(), anyLong()))
                .thenThrow(new IllegalStateException("boom"));
        when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

        TestCaseRunResult result =
                worker.execute(input, context, 0, responseColumns).getFirst();

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getResponseBody()).contains("REQUEST_RESOLUTION_ERROR");
    }

    @Test
    @DisplayName("Should mark the span ERROR when TurnLoopExecutor returns a row with ExecutionStatus.ERROR")
    void execute_turnLoopExecutorReturnsErrorRow_marksSpanError() {
        stubSpanChainReturns(span);

        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();
        TestCaseRunResult errorRow = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.ERROR)
                .logDetails("{\"error\":\"boom\"}")
                .build();
        when(turnLoopExecutor.execute(eq(input), eq(context), eq(0), eq(responseColumns), any(), anyLong()))
                .thenReturn(List.of(errorRow));

        worker.execute(input, context, 0, responseColumns);

        verify(span).setStatus(StatusCode.ERROR, "{\"error\":\"boom\"}");
    }

    @Test
    @DisplayName("Should not mark the span ERROR when TurnLoopExecutor returns only SUCCESS/FAILED rows")
    void execute_turnLoopExecutorReturnsOnlySuccessOrFailedRows_doesNotMarkSpanError() {
        stubSpanChainReturns(span);

        TestCaseRunInput input = buildTestCaseRunInput();
        EvaluationContext context = buildContext();
        List<ResponseColumnDefinitionDto> responseColumns = List.of();
        TestCaseRunResult successRow = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();
        TestCaseRunResult failedRow = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .executionStatus(ExecutionStatus.FAILED)
                .build();
        when(turnLoopExecutor.execute(eq(input), eq(context), eq(0), eq(responseColumns), any(), anyLong()))
                .thenReturn(List.of(successRow, failedRow));

        worker.execute(input, context, 0, responseColumns);

        verify(span, never()).setStatus(eq(StatusCode.ERROR), any());
    }

    private void stubSpanChainReturns(Span stubbedSpan) {
        when(openTelemetry
                        .getTracer(anyString())
                        .spanBuilder(anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .setAttribute(anyString(), anyString())
                        .startSpan())
                .thenReturn(stubbedSpan);
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
            when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

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
            when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

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
            when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

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
            when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

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
            when(responseColumnExtractor.extract(anyList(), anyString(), any()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

            TestCaseRunResult result =
                    worker.execute(input, context, 0, List.of()).getFirst();

            assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(result.getResponseBody()).contains("MCP_INVOCATION_ERROR");
        }
    }

    // ------------------------------------------------------------------
    // Helper methods
    // ------------------------------------------------------------------
    private EvaluationContext buildContext() {
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
                .build();
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
