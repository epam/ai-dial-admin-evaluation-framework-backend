package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.service.DashjoinJsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.runner.service.JsonataEvaluationService;
import com.epam.aidial.evaluation.runner.service.JsonataSourcePreprocessor;
import com.epam.aidial.evaluation.runner.service.RequestBodyEvaluator;
import com.epam.aidial.evaluation.runner.service.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.service.ResponseColumnTypeReconciler;
import com.epam.aidial.evaluation.runner.service.SerializedBody;
import com.epam.aidial.evaluation.runner.service.TemplateContentResolver;
import com.epam.aidial.evaluation.runner.service.TemplateVariableResolver;
import com.epam.aidial.evaluation.runner.util.QuietJsonService;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the unified turn loop's turn-count matrix (single-turn / multi-turn x no-per-turn-binding /
 * per-turn-binding), frame feedback across turns (Decision 5), body-evaluation-failure fail-fast (Decision
 * 8), cancellation, and the empty-turns degenerate row. Wires real
 * {@link RequestBodyEvaluator}/{@link ResponseColumnExtractor}/
 * JSONata internals (same approach as {@code RequestResolverTest}) so the frame-feedback assertions
 * exercise genuine JSONata evaluation rather than a canned stub. {@link DeploymentTurnInvoker} is mocked —
 * its own retry/streaming behavior is covered by its own unit tests.
 */
@DisplayName("TurnLoopExecutor")
@ExtendWith(MockitoExtension.class)
class TurnLoopExecutorTest {

    @Mock
    private DialCoreUrlBuilder urlBuilder;

    @Mock
    private RequestBodySerializerRegistry serializerRegistry;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution execution;

    @Mock
    private RunnerJsonbMapper jsonbMapper;

    @Mock
    private DeploymentTurnInvoker deploymentTurnInvoker;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    private TurnLoopExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        TemplateVariableResolver templateVariableResolver = new TemplateVariableResolver();
        DialFileRefResolver dialFileRefResolver = mock(DialFileRefResolver.class);
        TemplateContentResolver templateContentResolver =
                new TemplateContentResolver(templateVariableResolver, dialFileRefResolver);
        JsonataProperties jsonataProperties = new JsonataProperties();
        jsonataProperties.setEvaluationTimeoutMs(5000L);
        jsonataProperties.setMaxRecursionDepth(500);
        JsonataEvaluationService jsonataEvaluationService =
                new DashjoinJsonataEvaluationService(objectMapper, jsonataProperties);
        JsonataSourcePreprocessor jsonataSourcePreprocessor =
                new JsonataSourcePreprocessor(templateVariableResolver, dialFileRefResolver, objectMapper);
        RequestBodyEvaluator requestBodyEvaluator = new RequestBodyEvaluator(
                templateContentResolver, jsonataSourcePreprocessor, jsonataEvaluationService, objectMapper);
        RequestResolver requestResolver = new RequestResolver(templateContentResolver, requestBodyEvaluator);
        ResponseColumnExtractor responseColumnExtractor = new ResponseColumnExtractor(
                jsonataEvaluationService,
                new ResponseColumnTypeReconciler(),
                new ValidationWarningsSerializer(objectMapper),
                objectMapper);
        QuietJsonService jsonService = new QuietJsonService(objectMapper);
        PerTurnBindingDetector perTurnBindingDetector = new PerTurnBindingDetector();

        executor = new TurnLoopExecutor(
                requestResolver,
                urlBuilder,
                serializerRegistry,
                responseColumnExtractor,
                evaluationRunProperties,
                jsonbMapper,
                jsonService,
                deploymentTurnInvoker,
                perTurnBindingDetector,
                objectMapper,
                FIXED_CLOCK);
    }

    private void stubCommonInfra() {
        when(urlBuilder.buildUrl(any(), anyString())).thenReturn("/openai/deployments/gpt-4/chat/completions");
        when(evaluationRunProperties.getExecution()).thenReturn(execution);
        when(execution.getHeaderBlacklist()).thenReturn(List.of());
        when(serializerRegistry.serialize(any())).thenAnswer(inv -> {
            ResolvedBodyDto body = inv.getArgument(0);
            Object content = body instanceof ResolvedJsonBodyDto jsonBody ? jsonBody.getContent() : body;
            return new SerializedBody(MediaType.APPLICATION_JSON, content);
        });
    }

    private RequestTemplateDto jsonBodyTemplate(Map<String, Object> content) {
        return RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder().content(content).build())
                .build();
    }

    private RequestTemplateDto jsonataBodyTemplate(String jsonataContent) {
        return RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .jsonataContent(jsonataContent)
                        .build())
                .build();
    }

    private EvaluationContext.EvaluationContextBuilder baseContextBuilder() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .numberOfRuns(1)
                .numberOfTestCases(1)
                .maxRetries(0)
                .retryDelayMs(100L)
                .retryBackoffMultiplier(2.0)
                .maxRetryDelayMs(1000L)
                .resultBatchSize(100)
                .maxResponseSizeBytes(5_000_000L)
                .cancellationGracePeriodMs(5000L)
                .cancellationSignal(new AtomicBoolean(false))
                .createdAtMs(FIXED_CLOCK.millis())
                .snapshotDeploymentRef(DeploymentReferenceDto.builder()
                        .id("gpt-4")
                        .name("GPT-4")
                        .build())
                .snapshotEndpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build());
    }

    private TestCaseRunInput.TestCaseRunInputBuilder baseInputBuilder() {
        return TestCaseRunInput.builder().runId(UUID.randomUUID()).position(0).testCaseId(UUID.randomUUID());
    }

    /**
     * Builds a single-request ({@code totalRequests = 1}) spec from the given context's request-#0 fields —
     * the shape every pre-generalization test call used implicitly. {@code totalRequests = 1} keeps
     * requestIndex/totalRequests unstamped, matching the pre-change baseline. Callers pass an empty {@code
     * initialFrame} to {@link TurnLoopExecutor#execute} separately.
     */
    private RequestExecutionSpec singleRequestSpec(
            EvaluationContext context, List<ResponseColumnDefinitionDto> responseColumns) {
        return new RequestExecutionSpec(
                0,
                1,
                context.getSnapshotRequestName(),
                context.getSnapshotEndpointRef(),
                context.getSnapshotRequestTemplate(),
                context.getSnapshotInputBindings(),
                responseColumns);
    }

    @Test
    @DisplayName("Single-turn case: N=1, testCaseData persisted verbatim, turnIndex/totalTurns stay at defaults")
    void singleTurnCase_runsOnceWithDefaultTurnIndices() {
        stubCommonInfra();
        String rawDataJson = "{\"prompt\":\"hello\"}";
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("single-1")
                .testCaseData(rawDataJson)
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", "${{prompt}}")))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .snapshotTestCaseSchema(List.of())
                .build();

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(ExecutionStatus.SUCCESS, 200, "{\"choices\":[]}", 0, null));

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-1",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(row.getTurnIndex()).isZero();
        assertThat(row.getTotalTurns()).isEqualTo(1);
        assertThat(row.getTestCaseData()).isEqualTo(rawDataJson);
    }

    @Test
    @DisplayName("Multi-turn without per-turn binding collapses to N=1 built from shared data only")
    void multiTurnWithoutPerTurnBinding_collapsesToOneRow() {
        stubCommonInfra();
        String sharedDataJson = "{\"system\":\"SYS\"}";
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("mt-collapse")
                .testCaseData(sharedDataJson)
                .multiTurnData("[{\"prompt\":\"q0\"},{\"prompt\":\"q1\"}]")
                .build();
        // `prompt` is NOT bound — only `system` (shared, perTurn=false) is bound — so no per-turn binding.
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("system", "${{system}}")))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("system")
                        .dataField("system")
                        .build()))
                .snapshotTestCaseSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("system")
                                .type(SchemaFieldType.STRING)
                                .perTurn(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .perTurn(true)
                                .build()))
                .build();

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(ExecutionStatus.SUCCESS, 200, "{\"choices\":[]}", 0, null));

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-2",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(row.getTurnIndex()).isZero();
        assertThat(row.getTotalTurns()).isEqualTo(1);
        assertThat(row.getTestCaseData()).isEqualTo(sharedDataJson);
        assertThat(row.getRequestBody()).contains("SYS");
    }

    @Test
    @DisplayName("Multi-turn with per-turn binding runs N turns and accumulates history via the frame")
    void multiTurnWithPerTurnBinding_accumulatesHistoryAcrossTurns() {
        stubCommonInfra();
        String source = "{\"messages\": $append($history, [{\"role\": \"user\", \"content\": \"${{prompt}}\"}])}";
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("mt-history")
                .testCaseData(null)
                .multiTurnData("[{\"prompt\":\"q0\"},{\"prompt\":\"q1\"}]")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonataBodyTemplate(source))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .snapshotTestCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build()))
                .build();
        List<ResponseColumnDefinitionDto> responseColumns = List.of(ResponseColumnDefinitionDto.builder()
                .name("history")
                .expression("$append($_request.messages, [$_response.choices[0].message])")
                .type(SchemaFieldType.ARRAY)
                .build());

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(
                        ExecutionStatus.SUCCESS,
                        200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"reply-0\"}}]}",
                        0,
                        null))
                .thenReturn(new TurnOutcome(
                        ExecutionStatus.SUCCESS,
                        200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"reply-1\"}}]}",
                        0,
                        null));

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, responseColumns),
                        Map.of(),
                        "trace-3",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(2);
        TestCaseRunResult turn0 = results.get(0);
        TestCaseRunResult turn1 = results.get(1);
        assertThat(turn0.getTurnIndex()).isZero();
        assertThat(turn0.getTotalTurns()).isEqualTo(2);
        assertThat(turn1.getTurnIndex()).isEqualTo(1);
        assertThat(turn1.getTotalTurns()).isEqualTo(2);
        assertThat(turn0.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(turn1.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);

        // Turn 0's body has no history yet (unbound $history -> undefined-append).
        assertThat(turn0.getRequestBody()).contains("q0").doesNotContain("reply-0");
        // Turn 1's body carries turn 0's accumulated history: its user message + its assistant reply,
        // plus turn 1's own new user message — proving the frame-feedback wiring (Decision 5).
        assertThat(turn1.getRequestBody()).contains("q0").contains("reply-0").contains("q1");
    }

    @Test
    @DisplayName("Body-evaluation failure aborts the run with one ERROR row naming the cause")
    void bodyEvaluationFailure_producesErrorRowAndStopsRun() {
        // No stubCommonInfra(): evaluation fails before url/header/serializer resolution is ever reached.
        // JSONata source that evaluates to a scalar, not a JSON object -> RequestBodyEvaluationException.
        TestCaseRunInput input =
                baseInputBuilder().testCaseName("bad-body").testCaseData("{}").build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonataBodyTemplate("1 + 1"))
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .build();

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-4",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(row.getResponseBody()).contains("REQUEST_BODY_EVALUATION_ERROR");
        assertThat(row.getLogDetails()).contains("Request body evaluation failed");
        assertThat(row.getRequestBody()).isNull();
    }

    @Test
    @DisplayName(
            "Resolution failure outside body evaluation produces a REQUEST_RESOLUTION_ERROR envelope and stops the run")
    void resolutionFailure_producesResolutionErrorEnvelopeAndStopsRun() {
        when(urlBuilder.buildUrl(any(), anyString())).thenReturn("/openai/deployments/gpt-4/chat/completions");
        when(evaluationRunProperties.getExecution()).thenReturn(execution);
        when(execution.getHeaderBlacklist()).thenReturn(List.of());
        when(serializerRegistry.serialize(any()))
                .thenThrow(new IllegalStateException("Unsupported content type: application/xml"));

        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("bad-serializer")
                .testCaseData("{}")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", "hi")))
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .build();

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-8",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(row.getResponseBody()).contains("REQUEST_RESOLUTION_ERROR").contains("Unsupported content type");
        assertThat(row.getLogDetails()).contains("Request resolution failed");
    }

    @Test
    @DisplayName("Fail-fast: a failing turn stops the loop, earlier turns keep their SUCCESS rows")
    void failFast_stopsLoopAfterFailingTurn() {
        stubCommonInfra();
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("mt-fail")
                .testCaseData(null)
                .multiTurnData("[{\"prompt\":\"q0\"},{\"prompt\":\"q1\"},{\"prompt\":\"q2\"}]")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(
                        jsonBodyTemplate(Map.of("messages", List.of(Map.of("content", "${{prompt}}")))))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .snapshotTestCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build()))
                .build();

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(ExecutionStatus.SUCCESS, 200, "{\"choices\":[]}", 0, null))
                .thenReturn(new TurnOutcome(ExecutionStatus.FAILED, 500, "{\"error\":\"boom\"}", 0, null));

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-5",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(results.get(0).getTurnIndex()).isZero();
        assertThat(results.get(1).getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(results.get(1).getTurnIndex()).isEqualTo(1);
        assertThat(results.stream().anyMatch(r -> r.getTurnIndex() == 2)).isFalse();
    }

    @Test
    @DisplayName("A FAILED turn still runs response-column extraction against the error response body")
    void failedTurn_stillExtractsResponseColumnsFromErrorBody() {
        stubCommonInfra();
        TestCaseRunInput input =
                baseInputBuilder().testCaseName("http-error").testCaseData("{}").build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", "hi")))
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .build();
        List<ResponseColumnDefinitionDto> responseColumns = List.of(ResponseColumnDefinitionDto.builder()
                .name("errMsg")
                .expression("error.message")
                .build());

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(
                        new TurnOutcome(ExecutionStatus.FAILED, 500, "{\"error\":{\"message\":\"boom\"}}", 0, null));

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, responseColumns),
                        Map.of(),
                        "trace-7",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(row.getExtractedColumns()).contains("\"errMsg\":\"boom\"");
        assertThat(row.getExtractedColumns()).isNotEqualTo("{}");
    }

    @Test
    @DisplayName("Cancellation before the first turn produces no rows")
    void cancellationBeforeFirstTurn_producesNoRows() {
        TestCaseRunInput input =
                baseInputBuilder().testCaseName("cancelled").testCaseData("{}").build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", List.of())))
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .cancellationSignal(new AtomicBoolean(true))
                .build();

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-6",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Empty multiTurnData array yields one degenerate ERROR row")
    void emptyMultiTurnData_producesDegenerateErrorRow() {
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("mt-empty")
                .testCaseData(null)
                .multiTurnData("[]")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", List.of())))
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .build();

        List<TestCaseRunResult> results = executor.execute(
                        input,
                        context,
                        0,
                        singleRequestSpec(context, List.of()),
                        Map.of(),
                        "trace-7",
                        FIXED_CLOCK.millis())
                .rows();

        assertThat(results).hasSize(1);
        TestCaseRunResult row = results.getFirst();
        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(row.getTurnIndex()).isZero();
        assertThat(row.getTotalTurns()).isEqualTo(1);
        assertThat(row.getLogDetails()).contains("no readable turns");
    }

    // ------------------------------------------------------------------
    // RequestExecutionSpec/RequestExecutionResult generalization (add-multi-request-suite, section 5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Single-request chain (totalRequests=1) leaves requestIndex/totalRequests at builder defaults")
    void singleRequestChain_neverStampsRequestIndices() {
        stubCommonInfra();
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("single-req")
                .testCaseData("{\"prompt\":\"hi\"}")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(jsonBodyTemplate(Map.of("messages", "${{prompt}}")))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .snapshotTestCaseSchema(List.of())
                .build();
        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(ExecutionStatus.SUCCESS, 200, "{\"choices\":[]}", 0, null));

        RequestExecutionResult result = executor.execute(
                input, context, 0, singleRequestSpec(context, List.of()), Map.of(), "trace-9", FIXED_CLOCK.millis());

        assertThat(result.aborted()).isFalse();
        TestCaseRunResult row = result.rows().getFirst();
        assertThat(row.getRequestIndex()).isZero();
        assertThat(row.getTotalRequests()).isEqualTo(1);
    }

    @Test
    @DisplayName("A non-first request (totalRequests>1) stamps requestIndex/totalRequests and its turn-0 frame is"
            + " seeded from initialFrame")
    void nonFirstRequest_seedsFrameFromInitialFrameAndStampsRequestIndices() {
        stubCommonInfra();
        TestCaseRunInput input =
                baseInputBuilder().testCaseName("second-req").testCaseData("{}").build();
        EvaluationContext context = baseContextBuilder()
                .snapshotInputBindings(List.of())
                .snapshotTestCaseSchema(List.of())
                .build();
        RequestExecutionSpec spec = new RequestExecutionSpec(
                1,
                2,
                "second",
                context.getSnapshotEndpointRef(),
                jsonataBodyTemplate("{\"cfg\": $configId, \"messages\": \"hi\"}"),
                List.of(),
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()));
        Map<String, Object> initialFrame = Map.of("configId", "cfg-1");

        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(
                        ExecutionStatus.SUCCESS,
                        200,
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"reply\"}}]}",
                        0,
                        null));

        RequestExecutionResult result =
                executor.execute(input, context, 0, spec, initialFrame, "trace-10", FIXED_CLOCK.millis());

        assertThat(result.aborted()).isFalse();
        TestCaseRunResult row = result.rows().getFirst();
        // Turn-0 resolution frame was seeded from initialFrame: the body references $configId.
        assertThat(row.getRequestBody()).contains("cfg-1");
        // Stamped because totalRequests=2 (Decision 9).
        assertThat(row.getRequestIndex()).isEqualTo(1);
        assertThat(row.getTotalRequests()).isEqualTo(2);
        // Persisted extracted_columns is the accumulated union: the prior request's configId plus this
        // request's own answer column (Decision 4).
        assertThat(row.getExtractedColumns()).contains("\"configId\":\"cfg-1\"").contains("\"answer\":\"reply\"");
        // The returned accumulated frame carries both keys forward to the next request in the chain.
        assertThat(result.accumulatedFrame()).containsEntry("configId", "cfg-1").containsEntry("answer", "reply");
    }

    @Test
    @DisplayName("RequestExecutionResult.aborted is true when a turn fails, with rows produced so far returned")
    void requestExecutionResult_abortedTrueOnTurnFailure() {
        stubCommonInfra();
        TestCaseRunInput input = baseInputBuilder()
                .testCaseName("mt-fail-result")
                .testCaseData(null)
                .multiTurnData("[{\"prompt\":\"q0\"},{\"prompt\":\"q1\"}]")
                .build();
        EvaluationContext context = baseContextBuilder()
                .snapshotRequestTemplate(
                        jsonBodyTemplate(Map.of("messages", List.of(Map.of("content", "${{prompt}}")))))
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("prompt")
                        .build()))
                .snapshotTestCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build()))
                .build();
        when(deploymentTurnInvoker.invoke(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new TurnOutcome(ExecutionStatus.SUCCESS, 200, "{\"choices\":[]}", 0, null))
                .thenReturn(new TurnOutcome(ExecutionStatus.FAILED, 500, "{\"error\":\"boom\"}", 0, null));

        RequestExecutionResult result = executor.execute(
                input, context, 0, singleRequestSpec(context, List.of()), Map.of(), "trace-11", FIXED_CLOCK.millis());

        assertThat(result.aborted()).isTrue();
        assertThat(result.rows()).hasSize(2);
    }
}
