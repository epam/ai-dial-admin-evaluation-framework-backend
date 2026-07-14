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
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
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
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiTurnConversationExecutor per-turn emission (row-based)")
class MultiTurnConversationExecutorTest {

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

    private MultiTurnConversationExecutor executor;

    @BeforeEach
    void setUp() {
        final QuietJsonService jsonService = new QuietJsonService(objectMapper);
        executor = new MultiTurnConversationExecutor(
                resolvedRequestService,
                urlBuilder,
                serializerRegistry,
                responseColumnExtractor,
                evaluationRunProperties,
                jsonbMapper,
                jsonService,
                new DeploymentTurnInvoker(deploymentInvoker, jsonService),
                FIXED_CLOCK);
        lenient().when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/dep/chat/completions");
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(execution);
        lenient().when(execution.getHeaderBlacklist()).thenReturn(List.of());
        lenient()
                .when(serializerRegistry.serialize(any()))
                .thenAnswer(inv -> new SerializedBody(
                        MediaType.APPLICATION_JSON, ((ResolvedJsonBodyDto) inv.getArgument(0)).getContent()));
    }

    @Test
    @DisplayName("happy path emits one scalar SUCCESS row per turn with turnIndex/totalTurns and per-turn extraction")
    void happyPathTwoTurns() throws Exception {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(200, "assistant-1"));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(
                        new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-0\"}", "[]"),
                        new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-1\"}", "[]"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(2), context(), 0, columnsA(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(2);

        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), bodyCaptor.capture());
        List<Object> sentBodies = bodyCaptor.getAllValues();
        assertThat(messagesOf(sentBodies.get(0))).hasSize(1);
        assertThat(roleOf(messagesOf(sentBodies.get(0)).get(0))).isEqualTo("user");
        List<Object> turn1Sent = messagesOf(sentBodies.get(1));
        assertThat(turn1Sent).hasSize(3);
        assertThat(roleOf(turn1Sent.get(0))).isEqualTo("user");
        assertThat(roleOf(turn1Sent.get(1))).isEqualTo("assistant");
        assertThat(roleOf(turn1Sent.get(2))).isEqualTo("user");

        TestCaseRunResult row0 = results.get(0);
        assertThat(row0.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(row0.getTurnIndex()).isEqualTo(0);
        assertThat(row0.getTotalTurns()).isEqualTo(2);
        assertThat(row0.getTraceId()).isEqualTo("trace-1");
        assertThat(row0.getResponseStatusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(row0.getExtractedColumns()).get("a").asString())
                .isEqualTo("answer-0");
        assertThat(objectMapper.readTree(row0.getTestCaseData()).get("question").asString())
                .isEqualTo("q0");
        assertThat(contentOf(row0.getResponseBody())).isEqualTo("assistant-0");

        TestCaseRunResult row1 = results.get(1);
        assertThat(row1.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(row1.getTurnIndex()).isEqualTo(1);
        assertThat(row1.getTotalTurns()).isEqualTo(2);
        assertThat(objectMapper.readTree(row1.getExtractedColumns()).get("a").asString())
                .isEqualTo("answer-1");
        assertThat(objectMapper.readTree(row1.getTestCaseData()).get("question").asString())
                .isEqualTo("q1");
        assertThat(contentOf(row1.getResponseBody())).isEqualTo("assistant-1");
    }

    @Test
    @DisplayName("each turn resolves the template against its own frozen row data")
    void eachTurnResolvesFromItsOwnRow() {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(200, "assistant-1"));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(2), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(2);
        verify(resolvedRequestService, times(2)).resolve(any(), any(), dataCaptor.capture());
        List<Map<String, Object>> perTurnData = dataCaptor.getAllValues();
        assertThat(perTurnData.get(0).get("question")).isEqualTo("q0");
        assertThat(perTurnData.get(1).get("question")).isEqualTo("q1");
    }

    @Test
    @DisplayName("fail-fast on a mid-conversation HTTP failure: k SUCCESS rows + 1 ERROR row, remaining turns skipped")
    void failFastOnHttpFailure() {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(response(200, "assistant-0"), response(500, null));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{\"a\":\"answer-0\"}", "[]"));

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(3), context(), 0, columnsA(), "trace-1", FIXED_CLOCK.millis());

        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), any());
        assertThat(results).hasSize(2);

        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(results.get(0).getTurnIndex()).isEqualTo(0);

        TestCaseRunResult errorRow = results.get(1);
        assertThat(errorRow.getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(errorRow.getTurnIndex()).isEqualTo(1);
        assertThat(errorRow.getTotalTurns()).isEqualTo(3);
        assertThat(errorRow.getResponseStatusCode()).isEqualTo(500);
        assertThat(errorRow.getExtractedColumns()).isEqualTo("{}");
    }

    @Test
    @DisplayName("a 2xx response with no choices[0].message object yields one ERROR row for that turn")
    void failFastOnNoMessageObject() {
        when(resolvedRequestService.resolve(any(), any(), any())).thenReturn(resolvedTurn("turn-0"));
        DeploymentInvocationResult noMessage = new DeploymentInvocationResult(
                200, false, Map.of("choices", List.of(Map.of())), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(noMessage);

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(1), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(results.get(0).getTurnIndex()).isEqualTo(0);
        assertThat(results.get(0).getTotalTurns()).isEqualTo(1);
        assertThat(results.get(0).getExtractedColumns()).isEqualTo("{}");
    }

    @Test
    @DisplayName("appends the full assistant message verbatim (extra fields preserved) for a tool-call turn")
    void appendsFullAssistantMessageVerbatim() {
        when(resolvedRequestService.resolve(any(), any(), any()))
                .thenReturn(resolvedTurn("turn-0"), resolvedTurn("turn-1"));
        Map<String, Object> toolCall = Map.of(
                "id", "call_1", "type", "function", "function", Map.of("name", "get_weather", "arguments", "{}"));
        Map<String, Object> toolCallMessage =
                Map.of("role", "assistant", "tool_calls", List.of(toolCall), "refusal", "none");
        DeploymentInvocationResult turn0 = new DeploymentInvocationResult(
                200, false, Map.of("choices", List.of(Map.of("message", toolCallMessage))), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(turn0, response(200, "assistant-1"));
        when(responseColumnExtractor.extract(any(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]"));

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(2), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);

        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), bodyCaptor.capture());
        List<Object> turn1Sent = messagesOf(bodyCaptor.getAllValues().get(1));
        assertThat(turn1Sent).hasSize(3);
        JsonNode assistantNode = (JsonNode) turn1Sent.get(1);
        assertThat(assistantNode.get("role").asString()).isEqualTo("assistant");
        assertThat(assistantNode
                        .path("tool_calls")
                        .get(0)
                        .path("function")
                        .get("name")
                        .asString())
                .isEqualTo("get_weather");
        assertThat(assistantNode.get("refusal").asString()).isEqualTo("none");
    }

    @Test
    @DisplayName("a turn whose resolved 'messages' is not an array yields one ERROR row and no HTTP call")
    void nonListMessagesError() {
        when(resolvedRequestService.resolve(any(), any(), any())).thenReturn(resolvedTurnWithNonListMessages());

        List<TestCaseRunResult> results =
                executor.execute(inputWithTurns(1), context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(results.get(0).getTotalTurns()).isEqualTo(1);
        assertThat(results.get(0).getExtractedColumns()).isEqualTo("{}");
        verifyNoInteractions(deploymentInvoker);
    }

    @Test
    @DisplayName("an input with no readable frozen turns yields one degenerate 0/0 ERROR row and no HTTP call")
    void emptyTurnsYieldsZeroZeroError() {
        TestCaseRunInput input = TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("conv-1")
                .conversationId(UUID.randomUUID())
                .turns("[]")
                .build();

        List<TestCaseRunResult> results =
                executor.execute(input, context(), 0, List.of(), "trace-1", FIXED_CLOCK.millis());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(results.get(0).getTurnIndex()).isEqualTo(0);
        assertThat(results.get(0).getTotalTurns()).isEqualTo(0);
        verifyNoInteractions(deploymentInvoker);
    }

    private ResolvedRequestDto resolvedTurnWithNonListMessages() {
        Map<String, Object> content = new HashMap<>();
        content.put("model", "gpt-4");
        content.put("messages", "not-an-array");
        return ResolvedRequestDto.builder()
                .url("/chat")
                .body(ResolvedJsonBodyDto.builder().content(content).build())
                .build();
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

    private String contentOf(String responseBodyJson) throws Exception {
        return objectMapper
                .readTree(responseBodyJson)
                .path("choices")
                .get(0)
                .path("message")
                .get("content")
                .asString();
    }

    @SuppressWarnings("unchecked")
    private List<Object> messagesOf(Object sentBody) {
        return (List<Object>) ((Map<String, Object>) sentBody).get("messages");
    }

    @SuppressWarnings("unchecked")
    private String roleOf(Object message) {
        if (message instanceof JsonNode node) {
            return node.get("role").asString();
        }
        return (String) ((Map<String, Object>) message).get("role");
    }

    /** A single response column {@code a} so each turn's scalar extraction is keyed under that name. */
    private List<ResponseColumnDefinitionDto> columnsA() {
        return List.of(ResponseColumnDefinitionDto.builder()
                .name("a")
                .expression("choices[0].message.content")
                .build());
    }

    /**
     * Builds a conversation input of {@code n} frozen turns (as the snapshot phase writes them): an ordered
     * {@code turns} JSON array where each element carries a discrete row's {@code testCaseId}/{@code
     * testCaseName}/{@code turnIndex} and scalar {@code data} ({@code {"question":"q<i>"}}).
     */
    private TestCaseRunInput inputWithTurns(int n) {
        ArrayNode turns = objectMapper.createArrayNode();
        for (int i = 0; i < n; i++) {
            ObjectNode turn = objectMapper.createObjectNode();
            turn.put("testCaseId", UUID.randomUUID().toString());
            turn.put("testCaseName", "conv-1 / turn " + i);
            turn.put("turnIndex", i);
            ObjectNode data = objectMapper.createObjectNode();
            data.put("question", "q" + i);
            turn.set("data", data);
            turns.add(turn);
        }
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .conversationId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("conv-1 / turn 0")
                .totalTurns(n)
                .turns(objectMapper.writeValueAsString(turns))
                .build();
    }

    /** Single bindings shape: one {@code question} variable bound to the scalar {@code question} column. */
    private EvaluationContext context() {
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
                .snapshotInputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("question")
                        .dataField("question")
                        .build()))
                .snapshotRequestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/chat").build())
                .snapshotDeploymentRef(
                        DeploymentReferenceDto.builder().id("dep").build())
                .snapshotEndpointRef(EndpointContractDto.builder().build())
                .build();
    }
}
