package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
import com.epam.aidial.evaluation.runner.job.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.job.SseEvent;
import com.epam.aidial.evaluation.runner.job.SseEventParser;
import com.epam.aidial.evaluation.runner.job.SseParseResult;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.runner.service.McpRequestResolver;
import com.epam.aidial.evaluation.runner.service.McpResponseSerializer;
import com.epam.aidial.evaluation.runner.service.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.service.ResponseColumnExtractor;
import com.epam.aidial.evaluation.runner.service.SerializedBody;
import com.epam.aidial.evaluation.service.domain.TryItOutService.TryItOutValidationException;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import io.opentelemetry.api.OpenTelemetry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TryItOutService")
@ExtendWith(MockitoExtension.class)
class TryItOutServiceTest {

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private ResolvedRequestService resolvedRequestService;

    @Mock
    private RequestResolver requestResolver;

    @Mock
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Mock
    private McpToolInvoker mcpToolInvoker;

    @Mock
    private McpRequestResolver mcpRequestResolver;

    @Mock
    private McpResponseSerializer mcpResponseSerializer;

    @Mock
    private DialCoreUrlBuilder urlBuilder;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private ObjectMapper objectMapperMock;

    @Mock
    private RequestBodySerializerRegistry serializerRegistry;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OpenTelemetry openTelemetry;

    @Mock
    private GrafanaLinkBuilder grafanaLinkBuilder;

    @Mock
    private SseEventParser sseEventParser;

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution executionProperties;

    @Mock
    private DialCoreProperties dialCoreProperties;

    @Mock
    private DialCoreProperties.TryOut tryOutProperties;

    @Mock
    private SseEventProcessingProperties sseEventProcessingProperties;

    @Mock
    private ResponseColumnExtractor responseColumnExtractor;

    private TryItOutService service;
    private final ObjectMapper realObjectMapper = new ObjectMapper();

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID TEST_CASE_ID = UUID.randomUUID();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        service = new TryItOutService(
                testSuiteRepository,
                testCaseRepository,
                resolvedRequestService,
                requestResolver,
                deploymentInvoker,
                mcpToolInvoker,
                mcpRequestResolver,
                mcpResponseSerializer,
                urlBuilder,
                jsonbMapper,
                objectMapperMock,
                serializerRegistry,
                openTelemetry,
                grafanaLinkBuilder,
                FIXED_CLOCK,
                sseEventParser,
                evaluationRunProperties,
                dialCoreProperties,
                sseEventProcessingProperties,
                responseColumnExtractor);
        lenient()
                .when(serializerRegistry.serialize(any()))
                .thenReturn(new SerializedBody(MediaType.APPLICATION_JSON, Map.of("prompt", "Hello")));
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(executionProperties);
        lenient().when(executionProperties.getMaxResponseSizeBytes()).thenReturn(10 * 1024 * 1024L);
        lenient().when(dialCoreProperties.getTryOut()).thenReturn(tryOutProperties);
        lenient().when(tryOutProperties.getReadTimeoutMs()).thenReturn(30_000);
        lenient().when(sseEventProcessingProperties.getMaxTotalDurationMs()).thenReturn(3_600_000L);
        // Default: a single-request single-turn plan (no response columns), so existing single-shot tests
        // take the pre-chain fast path unchanged. Chain/multi-turn tests override this stub explicitly.
        lenient()
                .when(resolvedRequestService.planChain(any(), any(), any()))
                .thenReturn(new ResolvedRequestService.ChainPlan(List.of(new ResolvedRequestService.RequestPlan(
                        new RequestExecutionSpec(0, 1, null, null, null, List.of(), List.of()), List.of(Map.of())))));
        // Multi-turn frame-binding extraction serializes via the real mapper; delegate the mock accordingly.
        lenient()
                .when(objectMapperMock.writeValueAsString(any()))
                .thenAnswer(inv -> realObjectMapper.writeValueAsString(inv.getArgument(0)));
        lenient()
                .when(objectMapperMock.readTree(any(String.class)))
                .thenAnswer(inv -> realObjectMapper.readTree((String) inv.getArgument(0)));
    }

    private TestSuite buildSuite(String deploymentRefJson, String endpointRefJson, String requestTemplateJson) {
        return TestSuite.builder()
                .id(SUITE_ID)
                .name("Test Suite")
                .datasetId(UUID.randomUUID())
                .deploymentRef(deploymentRefJson)
                .endpointRef(endpointRefJson)
                .requestTemplate(requestTemplateJson)
                .inputBindings("[]")
                .validationWarnings("[]")
                .valid(true)
                .createdBy("test")
                .build();
    }

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("gpt-4")
                .name("GPT-4")
                .version("v1")
                .build();
    }

    private EndpointContractDto buildEndpointRef() {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/chat/completions")
                .build();
    }

    private ResolvedRequestDto buildResolvedRequest() {
        return ResolvedRequestDto.builder()
                .url("/chat/completions")
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("X-Custom")
                        .value("val")
                        .build()))
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("model")
                        .value("gpt-4")
                        .build()))
                .body(ResolvedJsonBodyDto.builder()
                        .content(Map.of("prompt", "Hello"))
                        .build())
                .warnings(List.of())
                .build();
    }

    private DeploymentInvocationResult nonStreamingResult(int statusCode, Object body) {
        return new DeploymentInvocationResult(statusCode, false, body, null, new HttpHeaders());
    }

    private DeploymentInvocationResult streamingResult(int statusCode, InputStream stream) {
        return new DeploymentInvocationResult(statusCode, true, null, stream, new HttpHeaders());
    }

    /**
     * One OpenAI-mode SSE chunk: no named event type (so the type defaults to {@code "message"}) and a
     * {@code choices[0].delta.content} payload — the shape {@code StreamingResponseAccumulator}
     * auto-detects and assembles into a non-streaming chat-completions document.
     */
    private SseEvent openAiDelta(String contentDelta) {
        return new SseEvent(
                "message",
                realObjectMapper.readTree("{\"choices\":[{\"delta\":{\"content\":\"" + contentDelta + "\"}}]}"));
    }

    @Nested
    @DisplayName("tryWithTestCase")
    class TryWithTestCase {

        @Test
        @DisplayName("should succeed with valid suite and test case (non-SSE)")
        void shouldSucceedWithValidData() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result).isNotNull();
            assertThat(result.getResolvedRequest().getUrl()).isEqualTo("/chat/completions");
            assertThat(result.getResponse().getStatusCode()).isEqualTo(200);
            assertThat(result.getResponse().getStreaming()).isNull();
            assertThat(result.getResponse().getEvents()).isNull();
            assertThat(result.getDurationMs()).isNotNull();
        }

        @Test
        @DisplayName("should throw EntityNotFoundException for non-existent suite")
        void shouldThrowNotFoundForMissingSuite() {
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("should throw ValidationException for missing deploymentRef")
        void shouldThrowForMissingDeploymentRef() {
            TestSuite suite = buildSuite(null, "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map((String) null)).thenReturn(null);
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Deployment reference is required");
        }

        @Test
        @DisplayName("should throw ValidationException for missing endpointRef")
        void shouldThrowForMissingEndpointRef() {
            TestSuite suite = buildSuite("{}", null, "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract((String) null)).thenReturn(null);

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Endpoint reference");
        }

        @Test
        @DisplayName("should throw ValidationException for missing requestTemplate")
        void shouldThrowForMissingTemplate() {
            TestSuite suite = buildSuite("{}", "{}", null);
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Request template is required");
        }

        @Test
        @DisplayName("should throw TryItOutValidationException for REQUIRED warnings")
        void shouldThrowForRequiredWarnings() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            ResolvedRequestDto resolved = ResolvedRequestDto.builder()
                    .url("/chat/completions")
                    .warnings(List.of(ValidationWarningDto.builder()
                            .fieldName("prompt")
                            .code(ValidationWarningCode.REQUIRED)
                            .message("Required variable 'prompt' has no binding")
                            .build()))
                    .build();
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(resolved);

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(TryItOutValidationException.class)
                    .hasMessageContaining("prompt");
        }

        @Test
        @DisplayName("should throw TryItOutValidationException and never invoke the deployment when the JSON body "
                + "failed JSONata evaluation (REQUEST_BODY_EVALUATION_ERROR warning)")
        void shouldAbortWithoutInvokingDeploymentForBodyEvaluationError() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            ResolvedRequestDto resolved = ResolvedRequestDto.builder()
                    .url("/chat/completions")
                    .body(ResolvedJsonBodyDto.builder().content(null).build())
                    .warnings(List.of(ValidationWarningDto.builder()
                            .path("$.requestTemplate.body")
                            .code(ValidationWarningCode.REQUEST_BODY_EVALUATION_ERROR)
                            .message("Failed to evaluate request body template: boom")
                            .build()))
                    .build();
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(resolved);

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(TryItOutValidationException.class);

            verifyNoInteractions(deploymentInvoker);
        }

        @Test
        @DisplayName("should throw ValidationException naming the chain element whose requestTemplate is null, "
                + "without planning or invoking anything")
        void shouldThrowForChainElementWithoutRequestTemplate() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            suite.setAdditionalRequests("[{}]");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            // Endpoint + method present, requestTemplate missing — the second per-element precondition.
            when(jsonbMapper.mapAdditionalRequests("[{}]"))
                    .thenReturn(List.of(RequestDefinitionDto.builder()
                            .name("broken")
                            .endpointRef(buildEndpointRef())
                            .build()));

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("additionalRequests[0]")
                    .hasMessageContaining("request template is required");

            verifyNoInteractions(deploymentInvoker);
            verify(resolvedRequestService, never()).planChain(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("tryWithTestCase multi-turn")
    class MultiTurnTryWithTestCase {

        private final RequestTemplateDto template =
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build();
        private final List<InputBindingDto> bindings = List.of();
        private final List<ResponseColumnDefinitionDto> responseColumns =
                List.of(ResponseColumnDefinitionDto.builder().name("history").build());

        private void setUpDeploymentSuite() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
        }

        /** A single-request plan over the given turns, carrying this class's template/bindings/columns. */
        private ResolvedRequestService.ChainPlan singleRequestPlan(
                List<Map<String, Object>> turnData, List<ResponseColumnDefinitionDto> columns) {
            return new ResolvedRequestService.ChainPlan(List.of(new ResolvedRequestService.RequestPlan(
                    new RequestExecutionSpec(0, 1, null, buildEndpointRef(), template, bindings, columns), turnData)));
        }

        @Test
        @DisplayName("executes every turn, threading extracted response columns as the next turn's frame bindings")
        void shouldExecuteAllTurnsThreadingFrameBindings() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"), Map.of("q", "3"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, responseColumns));

            Map<String, Object> frame1 = Map.of("history", "after-turn-0");
            Map<String, Object> frame2 = Map.of("history", "after-turn-1");
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(0)), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(1)), eq(frame1)))
                    .thenReturn(buildResolvedRequest());
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(2)), eq(frame2)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 2)));

            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", frame1))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", frame2));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getBody()).isEqualTo(Map.of("turn", 2));

            verify(requestResolver).resolveForRun(template, bindings, turnData.get(0), Map.of());
            verify(requestResolver).resolveForRun(template, bindings, turnData.get(1), frame1);
            verify(requestResolver).resolveForRun(template, bindings, turnData.get(2), frame2);

            assertThat(result.getHistory()).hasSize(3);
            assertThat(result.getHistory().get(0).getResponse().getBody()).isEqualTo(Map.of("turn", 0));
            assertThat(result.getHistory().get(1).getResponse().getBody()).isEqualTo(Map.of("turn", 1));
            assertThat(result.getHistory().get(2).getResponse().getBody()).isEqualTo(Map.of("turn", 2));
            assertThat(result.getHistory().get(2).getResponse()).isEqualTo(result.getResponse());
            assertThat(result.getHistory().get(2).getResolvedRequest()).isEqualTo(result.getResolvedRequest());
            assertThat(result.getHistory().get(2).getDurationMs()).isEqualTo(result.getDurationMs());
            assertThat(result.getHistory().get(2).getTraceId()).isEqualTo(result.getTraceId());
        }

        @Test
        @DisplayName("keeps an earlier turn's extracted column in the accumulated frame when a later turn's "
                + "extraction reproduces nothing for it (frame accumulates instead of being replaced)")
        void shouldKeepEarlierTurnsColumnInFrameWhenLaterTurnReproducesNothing() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"), Map.of("q", "3"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, responseColumns));

            // Turn 0 extracts "history"; turn 1's extraction reproduces nothing for it (empty values map) —
            // the delta-spec scenario "Frame accumulates instead of being replaced (behavioral fix)".
            Map<String, Object> frameAfterTurnZero = Map.of("history", "turn-0-value");
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(0)), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(1)), eq(frameAfterTurnZero)))
                    .thenReturn(buildResolvedRequest());
            // If the old replace-not-accumulate bug were present, turn 2 would resolve against an empty
            // frame (turn 1's own empty extraction) instead of still seeing turn 0's value.
            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(2)), eq(frameAfterTurnZero)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 2)));
            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"history\":\"turn-0-value\"}", "[]", frameAfterTurnZero))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

            service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verify(requestResolver).resolveForRun(template, bindings, turnData.get(2), frameAfterTurnZero);
        }

        @Test
        @DisplayName("stops at the first failed turn (fail-fast)")
        void shouldStopAtFirstFailedTurn() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"), Map.of("q", "3"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, responseColumns));

            when(requestResolver.resolveForRun(eq(template), eq(bindings), any(), any()))
                    .thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(500, Map.of("error", "boom")));
            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStatusCode()).isEqualTo(500);
            assertThat(result.getResponse().getBody()).isEqualTo(Map.of("error", "boom"));
            verify(deploymentInvoker, org.mockito.Mockito.times(2))
                    .invokeWithStreaming(any(), any(), any(), any(), any());

            assertThat(result.getHistory()).hasSize(2);
            assertThat(result.getHistory().get(0).getResponse().getStatusCode()).isEqualTo(200);
            assertThat(result.getHistory().get(1).getResponse()).isEqualTo(result.getResponse());
        }

        @Test
        @DisplayName("skips extraction on the failing turn even though response columns are defined: the failed "
                + "entry carries no extractedColumns/extractionWarnings and the extractor ran only for the "
                + "successful turn")
        void shouldSkipExtractionOnFailedTurnDespiteResponseColumns() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, responseColumns));

            when(requestResolver.resolveForRun(eq(template), eq(bindings), any(), any()))
                    .thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(500, Map.of("error", "boom")));
            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"history\":\"turn-0\"}", "[]", Map.of("history", "turn-0")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            // The extractor is invoked once — for turn 0 only; turn 1 failed, so the guard skipped it.
            verify(responseColumnExtractor, times(1)).extract(eq(responseColumns), anyString(), anyString());
            assertThat(result.getHistory()).hasSize(2);
            assertThat(result.getHistory().get(0).getExtractedColumns()).isNotNull();
            TryItOutResponseDto failedEntry = result.getHistory().get(1);
            assertThat(failedEntry.getResponse().getStatusCode()).isEqualTo(500);
            assertThat(failedEntry.getExtractedColumns()).isNull();
            assertThat(failedEntry.getExtractionWarnings()).isNull();
            assertThat(result.getExtractedColumns()).isNull();
            assertThat(result.getExtractionWarnings()).isNull();
        }

        @Test
        @DisplayName("omits requestIndex/totalRequests on every entry and the top level for a single-request "
                + "multi-turn suite, while stamping turnIndex/totalTurns")
        void shouldOmitRequestStampsForSingleRequestMultiTurnSuite() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, List.of()));

            when(requestResolver.resolveForRun(eq(template), eq(bindings), any(), any()))
                    .thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getHistory()).hasSize(2);
            for (TryItOutResponseDto entry : result.getHistory()) {
                assertThat(entry.getRequestIndex()).isNull();
                assertThat(entry.getTotalRequests()).isNull();
                assertThat(entry.getRequestName()).isNull();
                assertThat(entry.getTotalTurns()).isEqualTo(2);
            }
            assertThat(result.getRequestIndex()).isNull();
            assertThat(result.getTotalRequests()).isNull();
            assertThat(result.getRequestName()).isNull();
            assertThat(result.getTurnIndex()).isEqualTo(1);
            assertThat(result.getTotalTurns()).isEqualTo(2);
        }

        @Test
        @DisplayName("stops the sequence when a turn's request body fails JSONata evaluation")
        void shouldStopWhenRequestBodyEvaluationFails() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(turnData, responseColumns));

            when(requestResolver.resolveForRun(eq(template), eq(bindings), eq(turnData.get(0)), eq(Map.of())))
                    .thenThrow(new RequestBodyEvaluationException("boom"));
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStatusCode()).isEqualTo(0);
            verifyNoInteractions(deploymentInvoker);

            assertThat(result.getHistory()).hasSize(1);
            assertThat(result.getHistory().get(0).getResponse()).isEqualTo(result.getResponse());
        }

        @Test
        @DisplayName("a multi-turn test case collapsed to a single turn behaves like a single-turn case")
        void shouldBehaveLikeSingleTurnWhenPlanCollapsesToSingleTurn() {
            setUpDeploymentSuite();
            // Empty response columns: the fast path must not touch the extractor at all (design D6).
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(List.of(Map.of("shared", "value")), List.of()));
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getBody()).isEqualTo(Map.of("result", "ok"));
            verifyNoInteractions(responseColumnExtractor);
            assertThat(result.getHistory()).isNull();
        }

        @Test
        @DisplayName("rejects an MCP_TOOL suite whose test case has multi-turn data, without invoking the tool")
        void shouldRejectMcpSuiteWithMultiTurnTestCase() {
            TestSuite suite = TestSuite.builder()
                    .id(SUITE_ID)
                    .datasetId(UUID.randomUUID())
                    .suiteType(SuiteType.MCP_TOOL)
                    .mcpDeploymentRef("{}")
                    .toolRef("{}")
                    .argumentTemplate("{}")
                    .build();
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.mapMcpDeploymentRef("{}"))
                    .thenReturn(com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto.builder()
                            .id("mcp-deployment")
                            .build());
            when(jsonbMapper.mapToolRef("{}"))
                    .thenReturn(com.epam.aidial.evaluation.runner.dto.ToolReferenceDto.builder()
                            .name("search")
                            .build());
            when(jsonbMapper.mapArgumentTemplate("{}"))
                    .thenReturn(com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto.builder()
                            .build());

            TestCase testCase = TestCase.builder()
                    .id(TEST_CASE_ID)
                    .data("{}")
                    .multiTurnData("[{\"q\":\"1\"},{\"q\":\"2\"}]")
                    .build();
            when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, suite.getDatasetId()))
                    .thenReturn(Optional.of(testCase));

            assertThatThrownBy(() -> service.tryWithTestCase(SUITE_ID, TEST_CASE_ID))
                    .isInstanceOf(InvalidOperationException.class)
                    .hasMessageContaining("multi-turn");

            verifyNoInteractions(mcpToolInvoker);
        }
    }

    @Nested
    @DisplayName("tryWithTestCase multi-request chain")
    class MultiRequestTryWithTestCase {

        private final RequestTemplateDto template0 =
                RequestTemplateDto.builder().urlTemplate("/req0").build();
        private final RequestTemplateDto template1 =
                RequestTemplateDto.builder().urlTemplate("/req1").build();
        private final List<InputBindingDto> bindings = List.of();
        private final List<ResponseColumnDefinitionDto> columns0 =
                List.of(ResponseColumnDefinitionDto.builder().name("configId").build());

        private void setUpDeploymentSuite() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
        }

        /**
         * A two-request chain plan: request #0 unlabelled, request #1 named "followup" — both single-turn,
         * mirroring the delta-spec scenario "Identity stamps on a two-request chain".
         */
        private ResolvedRequestService.ChainPlan twoRequestPlan(
                HttpMethod method0,
                HttpMethod method1,
                List<ResponseColumnDefinitionDto> columnsForRequestZero,
                List<ResponseColumnDefinitionDto> columnsForRequestOne) {
            RequestExecutionSpec spec0 = new RequestExecutionSpec(
                    0,
                    2,
                    null,
                    EndpointContractDto.builder().method(method0).build(),
                    template0,
                    bindings,
                    columnsForRequestZero);
            RequestExecutionSpec spec1 = new RequestExecutionSpec(
                    1,
                    2,
                    "followup",
                    EndpointContractDto.builder().method(method1).build(),
                    template1,
                    bindings,
                    columnsForRequestOne);
            return new ResolvedRequestService.ChainPlan(List.of(
                    new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of()))));
        }

        @Test
        @DisplayName("threads request #0's real extracted columns into request #1's frame")
        void shouldThreadExtractedColumnsFromRequestZeroIntoRequestOnesFrame() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(twoRequestPlan(HttpMethod.POST, HttpMethod.POST, columns0, List.of()));

            when(requestResolver.resolveForRun(eq(template0), eq(bindings), eq(Map.of()), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            Map<String, Object> frameAfterRequestZero = Map.of("configId", "cfg-42");
            when(requestResolver.resolveForRun(eq(template1), eq(bindings), eq(Map.of()), eq(frameAfterRequestZero)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/openai/deployments/gpt-4/req");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("configId", "cfg-42")))
                    .thenReturn(nonStreamingResult(200, Map.of("done", true)));
            when(responseColumnExtractor.extract(eq(columns0), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"configId\":\"cfg-42\"}", "[]", frameAfterRequestZero));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verify(requestResolver).resolveForRun(template1, bindings, Map.of(), frameAfterRequestZero);
            assertThat(result.getHistory()).hasSize(2);
        }

        @Test
        @DisplayName("invokes each chain request with its own endpoint's HTTP method")
        void shouldInvokeEachRequestWithItsOwnHttpMethod() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(twoRequestPlan(HttpMethod.POST, HttpMethod.GET, List.of(), List.of()));

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)));

            service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verify(deploymentInvoker).invokeWithStreaming(eq(HttpMethod.POST), any(), any(), any(), any());
            verify(deploymentInvoker).invokeWithStreaming(eq(HttpMethod.GET), any(), any(), any(), any());
        }

        @Test
        @DisplayName("stops the chain and never invokes later requests when an earlier request fails (fail-fast)")
        void shouldFailFastMidChainAndNeverInvokeLaterRequests() {
            setUpDeploymentSuite();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 3, null, buildEndpointRef(), template0, bindings, List.of());
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 3, "second", buildEndpointRef(), template1, bindings, List.of());
            RequestTemplateDto template2 =
                    RequestTemplateDto.builder().urlTemplate("/req2").build();
            RequestExecutionSpec spec2 =
                    new RequestExecutionSpec(2, 3, "third", buildEndpointRef(), template2, bindings, List.of());
            ResolvedRequestService.ChainPlan plan = new ResolvedRequestService.ChainPlan(List.of(
                    new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec2, List.of(Map.of()))));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(plan);

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(500, Map.of("error", "boom")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getHistory()).hasSize(1);
            assertThat(result.getHistory().get(0).getResponse().getStatusCode()).isEqualTo(500);
            verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
            verify(requestResolver, times(1)).resolveForRun(any(), any(), any(), any());
        }

        @Test
        @DisplayName("stamps requestIndex/totalRequests/requestName on both entries of a two-request chain, "
                + "omitting turnIndex/totalTurns for its single-turn requests")
        void shouldStampIdentityOnTwoRequestChain() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(twoRequestPlan(HttpMethod.POST, HttpMethod.POST, List.of(), List.of()));

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            TryItOutResponseDto first = result.getHistory().get(0);
            assertThat(first.getRequestIndex()).isEqualTo(0);
            assertThat(first.getTotalRequests()).isEqualTo(2);
            assertThat(first.getRequestName()).isNull();
            assertThat(first.getTurnIndex()).isNull();
            assertThat(first.getTotalTurns()).isNull();

            TryItOutResponseDto second = result.getHistory().get(1);
            assertThat(second.getRequestIndex()).isEqualTo(1);
            assertThat(second.getTotalRequests()).isEqualTo(2);
            assertThat(second.getRequestName()).isEqualTo("followup");
            assertThat(second.getTurnIndex()).isNull();
            assertThat(second.getTotalTurns()).isNull();

            assertThat(result.getRequestIndex()).isEqualTo(second.getRequestIndex());
            assertThat(result.getRequestName()).isEqualTo(second.getRequestName());
        }

        @Test
        @DisplayName("omits extractedColumns/extractionWarnings on every entry and the top level when no "
                + "chain request defines response columns")
        void shouldOmitExtractionFieldsWhenNoRequestDefinesResponseColumns() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(twoRequestPlan(HttpMethod.POST, HttpMethod.POST, List.of(), List.of()));

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verifyNoInteractions(responseColumnExtractor);
            assertThat(result.getHistory().get(0).getExtractedColumns()).isNull();
            assertThat(result.getHistory().get(0).getExtractionWarnings()).isNull();
            assertThat(result.getHistory().get(1).getExtractedColumns()).isNull();
            assertThat(result.getHistory().get(1).getExtractionWarnings()).isNull();
            assertThat(result.getExtractedColumns()).isNull();
            assertThat(result.getExtractionWarnings()).isNull();
        }

        @Test
        @DisplayName("orders history request-major/turn-minor for a chain where only request #0 is multi-turn, "
                + "carrying request #0's fully accumulated turn extraction into the additional request's frame")
        void shouldOrderHistoryRequestMajorTurnMinorForMixedMultiTurnMultiRequestChain() {
            setUpDeploymentSuite();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 2, null, buildEndpointRef(), template0, bindings, columns0);
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 2, "followup", buildEndpointRef(), template1, bindings, List.of());
            List<Map<String, Object>> turnData0 = List.of(Map.of("q", "1"), Map.of("q", "2"));
            ResolvedRequestService.ChainPlan plan = new ResolvedRequestService.ChainPlan(List.of(
                    new ResolvedRequestService.RequestPlan(spec0, turnData0),
                    new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of()))));
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(plan);

            when(requestResolver.resolveForRun(eq(template0), eq(bindings), eq(turnData0.get(0)), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            Map<String, Object> frameAfterTurnZero = Map.of("configId", "cfg-1");
            when(requestResolver.resolveForRun(
                            eq(template0), eq(bindings), eq(turnData0.get(1)), eq(frameAfterTurnZero)))
                    .thenReturn(buildResolvedRequest());
            Map<String, Object> frameAfterTurnOne = Map.of("configId", "cfg-2");
            when(requestResolver.resolveForRun(eq(template1), eq(bindings), eq(Map.of()), eq(frameAfterTurnOne)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("turn", 1)))
                    .thenReturn(nonStreamingResult(200, Map.of("req", 1)));
            when(responseColumnExtractor.extract(eq(columns0), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", frameAfterTurnZero))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", frameAfterTurnOne));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getHistory()).hasSize(3);
            assertThat(result.getHistory().get(0).getRequestIndex()).isEqualTo(0);
            assertThat(result.getHistory().get(0).getTurnIndex()).isEqualTo(0);
            assertThat(result.getHistory().get(0).getTotalTurns()).isEqualTo(2);
            assertThat(result.getHistory().get(1).getRequestIndex()).isEqualTo(0);
            assertThat(result.getHistory().get(1).getTurnIndex()).isEqualTo(1);
            assertThat(result.getHistory().get(1).getTotalTurns()).isEqualTo(2);
            assertThat(result.getHistory().get(2).getRequestIndex()).isEqualTo(1);
            assertThat(result.getHistory().get(2).getTurnIndex()).isNull();
            assertThat(result.getHistory().get(2).getTotalTurns()).isNull();

            verify(requestResolver).resolveForRun(template1, bindings, Map.of(), frameAfterTurnOne);
        }

        @Test
        @DisplayName("does not stamp a blank requestName, while still stamping requestIndex/totalRequests")
        void shouldNotStampBlankRequestName() {
            setUpDeploymentSuite();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 2, null, buildEndpointRef(), template0, bindings, List.of());
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 2, "  ", buildEndpointRef(), template1, bindings, List.of());
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(new ResolvedRequestService.ChainPlan(List.of(
                            new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                            new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())))));

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("req", 0)))
                    .thenReturn(nonStreamingResult(200, Map.of("req", 1)));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            TryItOutResponseDto second = result.getHistory().get(1);
            assertThat(second.getRequestName()).isNull();
            assertThat(second.getRequestIndex()).isEqualTo(1);
            assertThat(second.getTotalRequests()).isEqualTo(2);
            assertThat(result.getRequestName()).isNull();
        }

        @Test
        @DisplayName("keeps an earlier REQUEST's extracted column in the frame when a later request's extraction "
                + "reproduces nothing for it (frame accumulates across the request boundary)")
        void shouldKeepEarlierRequestsColumnInFrameWhenLaterRequestReproducesNothing() {
            setUpDeploymentSuite();
            List<ResponseColumnDefinitionDto> columns1 =
                    List.of(ResponseColumnDefinitionDto.builder().name("other").build());
            RequestTemplateDto template2 =
                    RequestTemplateDto.builder().urlTemplate("/req2").build();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 3, null, buildEndpointRef(), template0, bindings, columns0);
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 3, "second", buildEndpointRef(), template1, bindings, columns1);
            RequestExecutionSpec spec2 =
                    new RequestExecutionSpec(2, 3, "third", buildEndpointRef(), template2, bindings, List.of());
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(new ResolvedRequestService.ChainPlan(List.of(
                            new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                            new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())),
                            new ResolvedRequestService.RequestPlan(spec2, List.of(Map.of())))));

            Map<String, Object> frameAfterRequestZero = Map.of("configId", "cfg-42");
            when(requestResolver.resolveForRun(eq(template0), eq(bindings), eq(Map.of()), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            when(requestResolver.resolveForRun(eq(template1), eq(bindings), eq(Map.of()), eq(frameAfterRequestZero)))
                    .thenReturn(buildResolvedRequest());
            // Request #1's extraction reproduces nothing, so request #2 must still see request #0's column —
            // proving accumulation across a REQUEST boundary, not just across turns.
            when(requestResolver.resolveForRun(eq(template2), eq(bindings), eq(Map.of()), eq(frameAfterRequestZero)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("configId", "cfg-42")))
                    .thenReturn(nonStreamingResult(200, Map.of("nothing", true)))
                    .thenReturn(nonStreamingResult(200, Map.of("done", true)));
            when(responseColumnExtractor.extract(eq(columns0), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"configId\":\"cfg-42\"}", "[]", frameAfterRequestZero));
            when(responseColumnExtractor.extract(eq(columns1), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{}", "[]", Map.of()));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verify(requestResolver).resolveForRun(template2, bindings, Map.of(), frameAfterRequestZero);
            assertThat(result.getHistory()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("try-out extraction fields")
    class ExtractionFields {

        private final List<ResponseColumnDefinitionDto> responseColumns =
                List.of(ResponseColumnDefinitionDto.builder().name("configId").build());

        private void setUpSingleRequestPlanWithColumns() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(new ResolvedRequestService.ChainPlan(List.of(new ResolvedRequestService.RequestPlan(
                            new RequestExecutionSpec(0, 1, null, null, null, List.of(), responseColumns),
                            List.of(Map.of())))));
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
        }

        @Test
        @DisplayName("exposes the top-level extractedColumns/extractionWarnings for a successful single-request "
                + "single-turn try-out")
        void shouldExposeTopLevelExtractedColumnsOnSuccess() {
            setUpSingleRequestPlanWithColumns();
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("configId", "cfg-42")));
            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"configId\":\"cfg-42\"}", "[]", Map.of("configId", "cfg-42")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getExtractedColumns().get("configId").asString()).isEqualTo("cfg-42");
            assertThat(result.getExtractionWarnings().isArray()).isTrue();
        }

        @Test
        @DisplayName(
                "preserves an explicit JSON null for a failed column extraction in the serialized " + "response body")
        void shouldPreserveExplicitNullForFailedExtractionInSerializedResponse() {
            setUpSingleRequestPlanWithColumns();
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("other", true)));
            when(responseColumnExtractor.extract(eq(responseColumns), anyString(), anyString()))
                    .thenReturn(
                            new ResponseColumnExtractor.ExtractionResult("{\"configId\":null}", "[]", new HashMap<>()));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getExtractedColumns().get("configId").isNull()).isTrue();
            var productionMapper = new JsonMapperConfiguration().objectMapper();
            String serialized = productionMapper.writeValueAsString(result);
            assertThat(serialized).contains("\"configId\":null");
        }
    }

    @Nested
    @DisplayName("tryWithVariables")
    class TryWithVariables {

        @Test
        @DisplayName("should succeed with valid variables (non-SSE)")
        void shouldSucceedWithValidVariables() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .body(JsonRequestBodyDto.builder()
                                    .content(Map.of("prompt", "${{prompt}}"))
                                    .build())
                            .build());
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithVariables(SUITE_ID, Map.of("prompt", "Hello"));

            assertThat(result).isNotNull();
            assertThat(result.getResponse().getStatusCode()).isEqualTo(200);
            assertThat(result.getResponse().getStreaming()).isNull();
        }

        @Test
        @DisplayName("should succeed with a jsonataContent-authored (instead of Map content-authored) template")
        void shouldSucceedWithJsonataContentAuthoredTemplate() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .body(JsonRequestBodyDto.builder()
                                    .jsonataContent("{\"prompt\": \"${{prompt}}\"}")
                                    .build())
                            .build());
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithVariables(SUITE_ID, Map.of("prompt", "Hello"));

            assertThat(result).isNotNull();
            assertThat(result.getResponse().getStatusCode()).isEqualTo(200);
            assertThat(result.getResponse().getStreaming()).isNull();
        }

        @Test
        @DisplayName("should skip null-value variables")
        @SuppressWarnings("unchecked")
        void shouldSkipNullValueVariables() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .build());
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            Map<String, Object> variables = new HashMap<>();
            variables.put("prompt", "Hello");
            variables.put("nullVar", null);
            service.tryWithVariables(SUITE_ID, variables);

            ArgumentCaptor<List<InputBindingDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(requestResolver).resolve(any(), captor.capture(), anyMap());
            List<InputBindingDto> bindings = captor.getValue();
            assertThat(bindings).hasSize(1);
            assertThat(bindings.get(0).getTemplateVariable()).isEqualTo("prompt");
        }

        @Test
        @DisplayName("should skip blank-key variables")
        @SuppressWarnings("unchecked")
        void shouldSkipBlankKeyVariables() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .build());
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            Map<String, Object> variables = new HashMap<>();
            variables.put("", "value");
            variables.put("  ", "value2");
            variables.put("valid", "ok");
            service.tryWithVariables(SUITE_ID, variables);

            ArgumentCaptor<List<InputBindingDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(requestResolver).resolve(any(), captor.capture(), anyMap());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getTemplateVariable()).isEqualTo("valid");
        }

        @Test
        @DisplayName("should convert headers and query params correctly")
        void shouldConvertHeadersAndQueryParams() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .build());

            ResolvedRequestDto resolved = ResolvedRequestDto.builder()
                    .url("/chat/completions")
                    .headers(List.of(
                            KeyValueTemplateDto.builder()
                                    .key("X-Custom")
                                    .value("v1")
                                    .build(),
                            KeyValueTemplateDto.builder()
                                    .key("X-Custom")
                                    .value("v2")
                                    .build()))
                    .queryParams(List.of(
                            KeyValueTemplateDto.builder().key("page").value("1").build()))
                    .body(ResolvedJsonBodyDto.builder()
                            .content(Map.of("prompt", "Hello"))
                            .build())
                    .warnings(List.of())
                    .build();
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(resolved);
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions")).thenReturn("/path");

            ArgumentCaptor<HttpHeaders> headersCaptor = ArgumentCaptor.forClass(HttpHeaders.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<MultiValueMap<String, String>> paramsCaptor = ArgumentCaptor.forClass(MultiValueMap.class);
            when(deploymentInvoker.invokeWithStreaming(
                            eq(HttpMethod.POST), eq("/path"), headersCaptor.capture(), paramsCaptor.capture(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            service.tryWithVariables(SUITE_ID, Map.of());

            HttpHeaders capturedHeaders = headersCaptor.getValue();
            assertThat(capturedHeaders.get("X-Custom")).containsExactly("v1", "v2");
            MultiValueMap<String, String> capturedParams = paramsCaptor.getValue();
            assertThat(capturedParams.get("page")).containsExactly("1");
        }

        @Test
        @DisplayName("executes the chain for a multi-request suite, applying the converted variables to every "
                + "request (a chain element's own inputBindings are ignored) and threading real extracted "
                + "columns as the next request's frame")
        void shouldExecuteChainWithVariablesAppliedToEveryRequest() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            suite.setAdditionalRequests("[{}]");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            RequestTemplateDto template0 =
                    RequestTemplateDto.builder().urlTemplate("/req0").build();
            RequestTemplateDto template1 =
                    RequestTemplateDto.builder().urlTemplate("/req1").build();
            // Deliberately different from the converted variables, to prove chain elements' own
            // inputBindings are ignored in variables mode (design D8).
            List<InputBindingDto> ignoredBindings = List.of(InputBindingDto.builder()
                    .templateVariable("ignored")
                    .constantValue("x")
                    .build());
            List<ResponseColumnDefinitionDto> columns0 = List.of(
                    ResponseColumnDefinitionDto.builder().name("configId").build());
            RequestDefinitionDto additionalRequest = RequestDefinitionDto.builder()
                    .name("followup")
                    .endpointRef(buildEndpointRef())
                    .requestTemplate(template1)
                    .inputBindings(ignoredBindings)
                    .responseColumns(List.of())
                    .build();
            when(jsonbMapper.mapAdditionalRequests("[{}]")).thenReturn(List.of(additionalRequest));

            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 2, null, buildEndpointRef(), template0, ignoredBindings, columns0);
            RequestExecutionSpec spec1 = new RequestExecutionSpec(
                    1, 2, "followup", buildEndpointRef(), template1, ignoredBindings, List.of());
            ResolvedRequestService.ChainPlan plan = new ResolvedRequestService.ChainPlan(List.of(
                    new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of()))));
            when(resolvedRequestService.planChainForVariables(eq(SUITE_ID), any()))
                    .thenReturn(plan);

            List<InputBindingDto> expectedBindings = List.of(InputBindingDto.builder()
                    .templateVariable("prompt")
                    .constantValue("Hello")
                    .build());
            when(requestResolver.resolveForRun(eq(template0), eq(expectedBindings), eq(Map.of()), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            Map<String, Object> frameAfterRequestZero = Map.of("configId", "cfg-42");
            when(requestResolver.resolveForRun(
                            eq(template1), eq(expectedBindings), eq(Map.of()), eq(frameAfterRequestZero)))
                    .thenReturn(buildResolvedRequest());

            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("configId", "cfg-42")))
                    .thenReturn(nonStreamingResult(200, Map.of("done", true)));
            when(responseColumnExtractor.extract(eq(columns0), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"configId\":\"cfg-42\"}", "[]", frameAfterRequestZero));

            TryItOutResponseDto result = service.tryWithVariables(SUITE_ID, Map.of("prompt", "Hello"));

            assertThat(result.getHistory()).hasSize(2);
            assertThat(result.getHistory().get(0).getRequestIndex()).isEqualTo(0);
            assertThat(result.getHistory().get(1).getRequestName()).isEqualTo("followup");
            verify(requestResolver).resolveForRun(template1, expectedBindings, Map.of(), frameAfterRequestZero);
            verify(requestResolver, never()).resolveForRun(any(), eq(ignoredBindings), any(), any());
        }

        @Test
        @DisplayName("stops the variables-mode chain and never invokes later requests when an earlier request "
                + "fails (fail-fast)")
        void shouldFailFastMidVariablesChain() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            suite.setAdditionalRequests("[{},{}]");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());

            RequestTemplateDto template0 =
                    RequestTemplateDto.builder().urlTemplate("/req0").build();
            RequestTemplateDto template1 =
                    RequestTemplateDto.builder().urlTemplate("/req1").build();
            RequestTemplateDto template2 =
                    RequestTemplateDto.builder().urlTemplate("/req2").build();
            RequestDefinitionDto additional1 = RequestDefinitionDto.builder()
                    .endpointRef(buildEndpointRef())
                    .requestTemplate(template1)
                    .build();
            RequestDefinitionDto additional2 = RequestDefinitionDto.builder()
                    .endpointRef(buildEndpointRef())
                    .requestTemplate(template2)
                    .build();
            when(jsonbMapper.mapAdditionalRequests("[{},{}]")).thenReturn(List.of(additional1, additional2));

            List<InputBindingDto> bindings = List.of();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 3, null, buildEndpointRef(), template0, bindings, List.of());
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 3, null, buildEndpointRef(), template1, bindings, List.of());
            RequestExecutionSpec spec2 =
                    new RequestExecutionSpec(2, 3, null, buildEndpointRef(), template2, bindings, List.of());
            ResolvedRequestService.ChainPlan plan = new ResolvedRequestService.ChainPlan(List.of(
                    new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())),
                    new ResolvedRequestService.RequestPlan(spec2, List.of(Map.of()))));
            when(resolvedRequestService.planChainForVariables(eq(SUITE_ID), any()))
                    .thenReturn(plan);

            when(requestResolver.resolveForRun(any(), any(), any(), any())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(500, Map.of("error", "boom")));

            TryItOutResponseDto result = service.tryWithVariables(SUITE_ID, Map.of("prompt", "Hello"));

            assertThat(result.getHistory()).hasSize(1);
            assertThat(result.getHistory().get(0).getResponse().getStatusCode()).isEqualTo(500);
            verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("SSE handling")
    class SseHandling {

        @Test
        @DisplayName("SSE response returns streaming=true with events and envelope body")
        void shouldReturnSseResponseWithStreamingFlagAndEvents() throws Exception {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions")).thenReturn("/path");

            InputStream sseStream =
                    new ByteArrayInputStream("data: {\"msg\":\"hi\"}\n\n".getBytes(StandardCharsets.UTF_8));
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, sseStream));

            JsonNode eventData = realObjectMapper.readTree("{\"msg\":\"hi\"}");
            List<SseEvent> parsedEvents = List.of(new SseEvent("message", eventData));
            when(sseEventParser.parse(eq(sseStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(parsedEvents, ExecutionStatus.SUCCESS, null));

            // objectMapperMock.createObjectNode() — use real mapper to build envelope
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenAnswer(inv -> realObjectMapper.createArrayNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStreaming()).isTrue();
            assertThat(result.getResponse().getEvents()).hasSize(1);
            assertThat(result.getResponse().getEvents().get(0).getEvent()).isEqualTo("message");
            assertThat(result.getResponse().getBody()).isNotNull();
        }

        @Test
        @DisplayName("Empty SSE stream returns streaming=true with empty events list")
        void shouldReturnEmptyEventsForEmptySseStream() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions")).thenReturn("/path");

            InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, emptyStream));
            when(sseEventParser.parse(eq(emptyStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(List.of(), ExecutionStatus.SUCCESS, null));
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenAnswer(inv -> realObjectMapper.createArrayNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStreaming()).isTrue();
            assertThat(result.getResponse().getEvents()).isEmpty();
        }

        @Test
        @DisplayName("SSE timeout returns partial events with streaming=true")
        void shouldReturnPartialEventsOnSseTimeout() throws Exception {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions")).thenReturn("/path");

            InputStream sseStream =
                    new ByteArrayInputStream("data: {\"partial\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, sseStream));

            JsonNode partial = realObjectMapper.readTree("{\"partial\":1}");
            List<SseEvent> partialEvents = List.of(new SseEvent("message", partial));
            when(sseEventParser.parse(eq(sseStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(partialEvents, ExecutionStatus.TIMEOUT, null));
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenAnswer(inv -> realObjectMapper.createArrayNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStreaming()).isTrue();
            assertThat(result.getResponse().getEvents()).hasSize(1);
        }

        @Test
        @DisplayName("SSE parse error returns partial events with streaming=true")
        void shouldReturnPartialEventsOnSseReadError() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions")).thenReturn("/path");

            InputStream sseStream = new ByteArrayInputStream(new byte[0]);
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, sseStream));
            when(sseEventParser.parse(eq(sseStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(List.of(), ExecutionStatus.ERROR, null));
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenAnswer(inv -> realObjectMapper.createArrayNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStreaming()).isTrue();
            assertThat(result.getResponse().getEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("streaming response-column extraction")
    class StreamingExtraction {

        private final RequestTemplateDto template0 =
                RequestTemplateDto.builder().urlTemplate("/req0").build();
        private final RequestTemplateDto template1 =
                RequestTemplateDto.builder().urlTemplate("/req1").build();
        private final List<InputBindingDto> bindings = List.of();
        private final List<ResponseColumnDefinitionDto> answerColumn = List.of(ResponseColumnDefinitionDto.builder()
                .name("answer")
                .expression("choices[0].message.content")
                .build());

        private void setUpDeploymentSuite() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(objectMapperMock.createObjectNode()).thenAnswer(inv -> realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenAnswer(inv -> realObjectMapper.createArrayNode());
        }

        private ResolvedRequestService.ChainPlan singleRequestPlan(List<ResponseColumnDefinitionDto> columns) {
            return new ResolvedRequestService.ChainPlan(List.of(new ResolvedRequestService.RequestPlan(
                    new RequestExecutionSpec(0, 1, null, buildEndpointRef(), template0, bindings, columns),
                    List.of(Map.of()))));
        }

        /** Stubs the invoker with one streaming response whose parse yields the given events + status. */
        private void stubStream(List<SseEvent> events, ExecutionStatus status, String truncationWarning) {
            InputStream sseStream = new ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, sseStream));
            when(sseEventParser.parse(eq(sseStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(events, status, truncationWarning));
        }

        @Test
        @DisplayName("extracts an OpenAI-mode stream's assembled choices[0].message document (not the events "
                + "envelope), while the response DTO still carries the events envelope + event list")
        void shouldExtractFromAssembledDocumentWhileDtoKeepsEventsEnvelope() {
            setUpDeploymentSuite();
            // Single-request/single-turn fast path with response columns.
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(answerColumn));
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            stubStream(List.of(openAiDelta("Hel"), openAiDelta("lo")), ExecutionStatus.SUCCESS, null);
            when(responseColumnExtractor.extract(eq(answerColumn), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"answer\":\"Hello\"}", "[]", Map.of("answer", "Hello")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            ArgumentCaptor<String> documentCaptor = ArgumentCaptor.forClass(String.class);
            verify(responseColumnExtractor).extract(eq(answerColumn), documentCaptor.capture(), anyString());
            JsonNode extractionDocument = realObjectMapper.readTree(documentCaptor.getValue());
            // The run path's assembled document: concatenated deltas under choices[0].message.content.
            assertThat(extractionDocument
                            .get("choices")
                            .get(0)
                            .get("message")
                            .get("content")
                            .asString())
                    .isEqualTo("Hello");
            assertThat(extractionDocument.has("events")).isFalse();

            // Display contract unchanged: the DTO body is still the {"events":[…]} envelope.
            JsonNode body = (JsonNode) result.getResponse().getBody();
            assertThat(body.get("events").size()).isEqualTo(2);
            assertThat(result.getResponse().getEvents()).hasSize(2);
            assertThat(result.getResponse().getStreamingStatus()).isNull();
            assertThat(result.getExtractedColumns().get("answer").asString()).isEqualTo("Hello");
        }

        @Test
        @DisplayName(
                "threads a streaming request's assembled-document extraction into the next chain request's " + "frame")
        void shouldThreadStreamingExtractionIntoNextRequestsFrame() {
            setUpDeploymentSuite();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 2, null, buildEndpointRef(), template0, bindings, answerColumn);
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 2, "followup", buildEndpointRef(), template1, bindings, List.of());
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(new ResolvedRequestService.ChainPlan(List.of(
                            new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                            new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())))));

            Map<String, Object> frameAfterRequestZero = Map.of("answer", "Hello");
            when(requestResolver.resolveForRun(eq(template0), eq(bindings), eq(Map.of()), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            when(requestResolver.resolveForRun(eq(template1), eq(bindings), eq(Map.of()), eq(frameAfterRequestZero)))
                    .thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");

            InputStream sseStream = new ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(streamingResult(200, sseStream))
                    .thenReturn(nonStreamingResult(200, Map.of("done", true)));
            when(sseEventParser.parse(eq(sseStream), anyLong(), anyLong(), anyLong()))
                    .thenReturn(new SseParseResult(
                            List.of(openAiDelta("Hel"), openAiDelta("lo")), ExecutionStatus.SUCCESS, null));
            when(responseColumnExtractor.extract(eq(answerColumn), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult(
                            "{\"answer\":\"Hello\"}", "[]", frameAfterRequestZero));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            ArgumentCaptor<String> documentCaptor = ArgumentCaptor.forClass(String.class);
            verify(responseColumnExtractor).extract(eq(answerColumn), documentCaptor.capture(), anyString());
            assertThat(realObjectMapper
                            .readTree(documentCaptor.getValue())
                            .get("choices")
                            .get(0)
                            .get("message")
                            .get("content")
                            .asString())
                    .isEqualTo("Hello");
            verify(requestResolver).resolveForRun(template1, bindings, Map.of(), frameAfterRequestZero);
            assertThat(result.getHistory()).hasSize(2);
        }

        @Test
        @DisplayName("a non-OpenAI-mode stream extracts against the events envelope the run path would see")
        void shouldExtractFromEventsEnvelopeForStructuredStream() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(answerColumn));
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            stubStream(
                    List.of(new SseEvent("process_rules", realObjectMapper.readTree("{\"stage\":\"one\"}"))),
                    ExecutionStatus.SUCCESS,
                    null);
            when(responseColumnExtractor.extract(eq(answerColumn), anyString(), anyString()))
                    .thenReturn(new ResponseColumnExtractor.ExtractionResult("{\"answer\":\"one\"}", "[]", Map.of()));

            service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            ArgumentCaptor<String> documentCaptor = ArgumentCaptor.forClass(String.class);
            verify(responseColumnExtractor).extract(eq(answerColumn), documentCaptor.capture(), anyString());
            JsonNode extractionDocument = realObjectMapper.readTree(documentCaptor.getValue());
            assertThat(extractionDocument.get("events").get(0).get("event").asString())
                    .isEqualTo("process_rules");
            assertThat(extractionDocument.has("choices")).isFalse();
        }

        @Test
        @DisplayName("a truncated stream is a failed invocation: extraction is skipped, the condition is surfaced, "
                + "and the chain's next request is never invoked")
        void shouldFailFastAndSkipExtractionForTruncatedStreamMidChain() {
            setUpDeploymentSuite();
            RequestExecutionSpec spec0 =
                    new RequestExecutionSpec(0, 2, null, buildEndpointRef(), template0, bindings, answerColumn);
            RequestExecutionSpec spec1 =
                    new RequestExecutionSpec(1, 2, "followup", buildEndpointRef(), template1, bindings, List.of());
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(new ResolvedRequestService.ChainPlan(List.of(
                            new ResolvedRequestService.RequestPlan(spec0, List.of(Map.of())),
                            new ResolvedRequestService.RequestPlan(spec1, List.of(Map.of())))));
            when(requestResolver.resolveForRun(eq(template0), eq(bindings), eq(Map.of()), eq(Map.of())))
                    .thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            stubStream(
                    List.of(openAiDelta("Hel")),
                    ExecutionStatus.ERROR,
                    "Response truncated: accumulated 10 bytes, limit 5");

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verifyNoInteractions(responseColumnExtractor);
            verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
            verify(requestResolver, times(1)).resolveForRun(any(), any(), any(), any());
            verify(requestResolver, never()).resolveForRun(eq(template1), any(), any(), any());

            assertThat(result.getHistory()).hasSize(1);
            assertThat(result.getExtractedColumns()).isNull();
            assertThat(result.getExtractionWarnings()).isNull();
            assertThat(result.getResponse().getStreamingStatus()).isEqualTo(ExecutionStatus.ERROR);
            assertThat(result.getResponse().getTruncationWarning())
                    .isEqualTo("Response truncated: accumulated 10 bytes, limit 5");
            // The events received before truncation stay visible.
            assertThat(result.getResponse().getEvents()).hasSize(1);
        }

        @Test
        @DisplayName("a timed-out stream skips extraction on the single-request fast path and reports "
                + "streamingStatus=TIMEOUT")
        void shouldSkipExtractionForTimedOutStreamOnFastPath() {
            setUpDeploymentSuite();
            when(resolvedRequestService.planChain(eq(SUITE_ID), eq(TEST_CASE_ID), any()))
                    .thenReturn(singleRequestPlan(answerColumn));
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            stubStream(List.of(openAiDelta("Hel")), ExecutionStatus.TIMEOUT, null);

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verifyNoInteractions(responseColumnExtractor);
            assertThat(result.getExtractedColumns()).isNull();
            assertThat(result.getExtractionWarnings()).isNull();
            assertThat(result.getResponse().getStreamingStatus()).isEqualTo(ExecutionStatus.TIMEOUT);
            assertThat(result.getResponse().getEvents()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("timing")
    class Timing {

        @Test
        @DisplayName("durationMs is measured")
        void durationMsIsMeasured() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(jsonbMapper.mapRequestTemplate("{}"))
                    .thenReturn(RequestTemplateDto.builder()
                            .urlTemplate("/chat/completions")
                            .build());
            when(requestResolver.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            TryItOutResponseDto result = service.tryWithVariables(SUITE_ID, Map.of("prompt", "Hi"));

            assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("traceId")
    class TraceIdTests {

        @Test
        @DisplayName("should include traceId in response when tracer returns a real trace ID")
        void shouldIncludeTraceIdWhenTracerReturnsRealTraceId() {
            String expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            when(openTelemetry
                            .getTracer(anyString())
                            .spanBuilder(anyString())
                            .setAttribute(anyString(), anyString())
                            .startSpan()
                            .getSpanContext()
                            .isValid())
                    .thenReturn(true);
            when(openTelemetry
                            .getTracer(anyString())
                            .spanBuilder(anyString())
                            .setAttribute(anyString(), anyString())
                            .startSpan()
                            .getSpanContext()
                            .getTraceId())
                    .thenReturn(expectedTraceId);

            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getTraceId()).isEqualTo(expectedTraceId);
        }

        @Test
        @DisplayName("should set eval.suite.id span attribute")
        void shouldSetEvalSuiteIdSpanAttribute() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            var spanBuilder =
                    openTelemetry.getTracer("com.epam.aidial.evaluation").spanBuilder("try-it-out.invoke");
            org.mockito.Mockito.clearInvocations(spanBuilder);

            service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            verify(spanBuilder).setAttribute("eval.suite.id", SUITE_ID.toString());
        }

        @Test
        @DisplayName("should return null traceId when span context is invalid (no-op span)")
        void shouldReturnNullTraceIdForNoopSpan() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
            when(resolvedRequestService.resolveRequest(SUITE_ID, TEST_CASE_ID)).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl("gpt-4", "/chat/completions"))
                    .thenReturn("/openai/deployments/gpt-4/chat/completions");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, Map.of("result", "ok")));

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getTraceId()).isNull();
        }
    }
}
