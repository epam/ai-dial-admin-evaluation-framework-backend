package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
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
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiStepConversationExecutor turn loop")
class MultiStepConversationExecutorTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ResolvedRequestService resolvedRequestService;

    @Mock
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Mock
    private DialCoreUrlBuilder urlBuilder;

    @Mock
    private RequestBodySerializerRegistry serializerRegistry;

    @Mock
    private ResponseColumnExtractor responseColumnExtractor;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution execution;

    @Mock
    private JsonbMapper jsonbMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MultiStepConversationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new MultiStepConversationExecutor(
                resolvedRequestService,
                deploymentInvoker,
                urlBuilder,
                serializerRegistry,
                responseColumnExtractor,
                evaluationRunProperties,
                jsonbMapper,
                objectMapper,
                FIXED_CLOCK);
        lenient().when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/dep/chat/completions");
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(execution);
        lenient().when(execution.getHeaderBlacklist()).thenReturn(List.of());
        // serializerRegistry returns the (already history-merged) content map so we can assert what was sent
        lenient()
                .when(serializerRegistry.serialize(any()))
                .thenAnswer(inv -> new SerializedBody(
                        MediaType.APPLICATION_JSON, ((ResolvedJsonBodyDto) inv.getArgument(0)).getContent()));
    }

    @Test
    @DisplayName("happy path accumulates history, resends full history, appends assistant, and extracts per step")
    void happyPathTwoSteps() throws Exception {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(200, "assistant-1"));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(
                        new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-0\"}", "[]"),
                        new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-1\"}", "[]"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        TestCaseRunResult result =
                executor.execute(inputWithTurns(2), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), bodyCaptor.capture());
        List<Object> sentBodies = bodyCaptor.getAllValues();

        // Full-history resend: step 0 sends [user-0]; step 1 sends [user-0, assistant-0, user-1]
        assertThat(messagesOf(sentBodies.get(0))).hasSize(1);
        assertThat(roleOf(messagesOf(sentBodies.get(0)).get(0))).isEqualTo("user");
        List<Object> step1Sent = messagesOf(sentBodies.get(1));
        assertThat(step1Sent).hasSize(3);
        assertThat(roleOf(step1Sent.get(0))).isEqualTo("user");
        assertThat(roleOf(step1Sent.get(1))).isEqualTo("assistant");
        assertThat(roleOf(step1Sent.get(2))).isEqualTo("user");

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getResponseStatusCode()).isEqualTo(200);
        assertThat(result.getTraceId()).isEqualTo("trace-1");

        // responseBody = the last turn's raw response body (not the reconstructed history)
        JsonNode responseBody = objectMapper.readTree(result.getResponseBody());
        assertThat(responseBody
                        .path("choices")
                        .get(0)
                        .path("message")
                        .get("content")
                        .asString())
                .isEqualTo("assistant-1");

        // extractedColumns = per-step array of length 2
        JsonNode extracted = objectMapper.readTree(result.getExtractedColumns());
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.size()).isEqualTo(2);
        assertThat(extracted.get(0).get("a").asString()).isEqualTo("answer-0");
        assertThat(extracted.get(1).get("a").asString()).isEqualTo("answer-1");
    }

    @Test
    @DisplayName("fail-fast on a mid-conversation HTTP failure stops remaining steps with partial persistence")
    void failFastOnHttpFailure() throws Exception {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(500, null));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-0\"}", "[]"));

        TestCaseRunResult result =
                executor.execute(inputWithTurns(2), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        // step 1 (index 1) failed; step 2 would not exist anyway, but no third invocation
        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), any());
        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getResponseStatusCode()).isEqualTo(500);

        // responseBody = the failed last turn's raw response body
        JsonNode responseBody = objectMapper.readTree(result.getResponseBody());
        assertThat(responseBody
                        .path("choices")
                        .get(0)
                        .path("message")
                        .get("role")
                        .asString())
                .isEqualTo("assistant");

        // only the completed step's extraction is kept
        JsonNode extracted = objectMapper.readTree(result.getExtractedColumns());
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("fail-fast when a 2xx response has no extractable assistant content; empty extractedColumns array")
    void failFastOnUnextractableAssistant() throws Exception {
        when(resolvedRequestService.resolve(any(), any(), any())).thenReturn(resolvedTurn("turn-0"));
        // 200 but body has no choices[0].message.content
        DeploymentInvocationResult noContent = new DeploymentInvocationResult(
                200,
                false,
                Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant")))),
                null,
                new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(noContent);

        TestCaseRunResult result =
                executor.execute(inputWithTurns(1), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        // responseBody = the raw 2xx response body (which lacked extractable assistant content)
        JsonNode responseBody = objectMapper.readTree(result.getResponseBody());
        assertThat(responseBody
                        .path("choices")
                        .get(0)
                        .path("message")
                        .get("role")
                        .asString())
                .isEqualTo("assistant");
        assertThat(responseBody.path("choices").get(0).path("message").has("content"))
                .isFalse();
        // step 0 never completed → empty per-step array
        JsonNode extracted = objectMapper.readTree(result.getExtractedColumns());
        assertThat(extracted.isArray()).isTrue();
        assertThat(extracted.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("array columns iterate per turn while scalar columns broadcast unchanged")
    void scalarBroadcastAcrossTurns() throws Exception {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(200, "assistant-1"));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        EvaluationContext context = contextWithBindings(List.of(
                InputBindingDto.builder()
                        .templateVariable("turn")
                        .dataField("turns")
                        .build(),
                InputBindingDto.builder()
                        .templateVariable("sys")
                        .dataField("system")
                        .build()));
        TestCaseRunInput input =
                inputWithData(Map.<String, Object>of("turns", List.of("q0", "q1"), "system", "be concise"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        TestCaseRunResult result = executor.execute(input, context, 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        verify(resolvedRequestService, times(2)).resolve(any(), any(), dataCaptor.capture());
        List<Map<String, Object>> perTurnData = dataCaptor.getAllValues();
        // array column iterates: element i per turn
        assertThat(perTurnData.get(0).get("turns")).isEqualTo("q0");
        assertThat(perTurnData.get(1).get("turns")).isEqualTo("q1");
        // scalar column broadcasts unchanged on every turn
        assertThat(perTurnData.get(0).get("system")).isEqualTo("be concise");
        assertThat(perTurnData.get(1).get("system")).isEqualTo("be concise");
    }

    @Test
    @DisplayName("mismatched array lengths fail only that test case with an ERROR result and no calls")
    void mismatchedArrayLengthsError() {
        EvaluationContext context = contextWithBindings(List.of(
                InputBindingDto.builder().templateVariable("a").dataField("as").build(),
                InputBindingDto.builder().templateVariable("b").dataField("bs").build()));
        TestCaseRunInput input = inputWithData(Map.<String, Object>of("as", List.of("x", "y"), "bs", List.of("z")));

        TestCaseRunResult result = executor.execute(input, context, 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(result.getExtractedColumns()).isEqualTo("[]");
        verifyNoInteractions(deploymentInvoker);
    }

    @Test
    @DisplayName("no array-valued bound column fails only that test case with an ERROR result and no calls")
    void noArrayColumnError() {
        TestCaseRunInput input = inputWithData(Map.<String, Object>of("turns", "just a string"));

        TestCaseRunResult result = executor.execute(input, context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        verifyNoInteractions(deploymentInvoker);
    }

    @Test
    @DisplayName("turn count over the cap fails only that test case with an ERROR result and no calls")
    void turnCountOverCapError() {
        TestCaseRunInput input = inputWithTurns(ValidationConstants.MAX_CONVERSATION_STEPS + 1);

        TestCaseRunResult result = executor.execute(input, context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(result.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        verifyNoInteractions(deploymentInvoker);
    }

    private ResolvedRequestDto resolvedTurn(String userContent) {
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);
        Map<String, Object> content = new HashMap<>();
        content.put("model", "gpt-4");
        content.put("messages", new ArrayList<>(List.of(userMsg)));
        return ResolvedRequestDto.builder()
                .url("/chat")
                .body(ResolvedJsonBodyDto.builder().content(content).build())
                .build();
    }

    private DeploymentInvocationResult response(int status, String assistantContent) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        if (assistantContent != null) {
            message.put("content", assistantContent);
        }
        Map<String, Object> body = Map.of("choices", List.of(Map.of("message", message)));
        return new DeploymentInvocationResult(status, false, body, null, new HttpHeaders());
    }

    @SuppressWarnings("unchecked")
    private List<Object> messagesOf(Object sentBody) {
        return (List<Object>) ((Map<String, Object>) sentBody).get("messages");
    }

    @SuppressWarnings("unchecked")
    private String roleOf(Object message) {
        return (String) ((Map<String, Object>) message).get("role");
    }

    /** Builds an input whose single array-valued column {@code turns} has {@code n} elements → {@code n} turns. */
    private TestCaseRunInput inputWithTurns(int n) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            values.add("q" + i);
        }
        return inputWithData(Map.<String, Object>of("turns", values));
    }

    private TestCaseRunInput inputWithData(Map<String, Object> data) {
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("tc-1")
                .testCaseData(objectMapper.writeValueAsString(data))
                .build();
    }

    /** Single-step-shaped bindings: one {@code turn} variable bound to the array-valued {@code turns} column. */
    private EvaluationContext context() {
        return contextWithBindings(List.of(InputBindingDto.builder()
                .templateVariable("turn")
                .dataField("turns")
                .build()));
    }

    private EvaluationContext contextWithBindings(List<InputBindingDto> bindings) {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .cancellationSignal(new AtomicBoolean(false))
                .maxRetries(0)
                .retryDelayMs(0)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(0)
                .maxResponseSizeBytes(10_000_000L)
                .createdAtMs(FIXED_CLOCK.millis())
                .snapshotMultiStep(true)
                .snapshotInputBindings(bindings)
                .snapshotRequestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/chat").build())
                .snapshotDeploymentRef(
                        DeploymentReferenceDto.builder().id("dep").build())
                .snapshotEndpointRef(EndpointContractDto.builder().build())
                .build();
    }
}
