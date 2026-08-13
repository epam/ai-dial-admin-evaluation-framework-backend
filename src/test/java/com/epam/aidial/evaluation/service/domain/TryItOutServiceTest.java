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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
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
import tools.jackson.databind.node.ObjectNode;

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
        // Default: a single-turn plan, so existing single-shot tests take the pre-multi-turn code path
        // unchanged. Multi-turn tests override this stub explicitly.
        lenient()
                .when(resolvedRequestService.planTurns(any(), any()))
                .thenReturn(new ResolvedRequestService.TurnPlan(null, null, null, List.of(Map.of())));
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
                .disabledTestCaseIds("[]")
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
    }

    @Nested
    @DisplayName("tryWithTestCase multi-turn")
    class MultiTurnTryWithTestCase {

        private final RequestTemplateDto template =
                RequestTemplateDto.builder().urlTemplate("/chat/completions").build();
        private final List<InputBindingDto> bindings = List.of();
        private final List<ResponseColumnDefinitionDto> responseColumns = List.of();

        private void setUpDeploymentSuite() {
            TestSuite suite = buildSuite("{}", "{}", "{}");
            when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
            when(jsonbMapper.map("{}")).thenReturn(buildDeploymentRef());
            when(jsonbMapper.mapEndpointContract("{}")).thenReturn(buildEndpointRef());
        }

        @Test
        @DisplayName("executes every turn, threading extracted response columns as the next turn's frame bindings")
        void shouldExecuteAllTurnsThreadingFrameBindings() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"), Map.of("q", "3"));
            when(resolvedRequestService.planTurns(SUITE_ID, TEST_CASE_ID))
                    .thenReturn(new ResolvedRequestService.TurnPlan(template, bindings, responseColumns, turnData));

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
        @DisplayName("stops at the first failed turn (fail-fast)")
        void shouldStopAtFirstFailedTurn() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"), Map.of("q", "3"));
            when(resolvedRequestService.planTurns(SUITE_ID, TEST_CASE_ID))
                    .thenReturn(new ResolvedRequestService.TurnPlan(template, bindings, responseColumns, turnData));

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
        @DisplayName("stops the sequence when a turn's request body fails JSONata evaluation")
        void shouldStopWhenRequestBodyEvaluationFails() {
            setUpDeploymentSuite();
            List<Map<String, Object>> turnData = List.of(Map.of("q", "1"), Map.of("q", "2"));
            when(resolvedRequestService.planTurns(SUITE_ID, TEST_CASE_ID))
                    .thenReturn(new ResolvedRequestService.TurnPlan(template, bindings, responseColumns, turnData));

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
            when(resolvedRequestService.planTurns(SUITE_ID, TEST_CASE_ID))
                    .thenReturn(new ResolvedRequestService.TurnPlan(
                            template, bindings, responseColumns, List.of(Map.of("shared", "value"))));
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
            ObjectNode mockEnvelope = realObjectMapper.createObjectNode();
            mockEnvelope.set("events", realObjectMapper.createArrayNode());
            when(objectMapperMock.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenReturn(realObjectMapper.createArrayNode());

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
            when(objectMapperMock.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenReturn(realObjectMapper.createArrayNode());

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
            when(objectMapperMock.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenReturn(realObjectMapper.createArrayNode());

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
            when(objectMapperMock.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());
            when(objectMapperMock.createArrayNode()).thenReturn(realObjectMapper.createArrayNode());

            TryItOutResponseDto result = service.tryWithTestCase(SUITE_ID, TEST_CASE_ID);

            assertThat(result.getResponse().getStreaming()).isTrue();
            assertThat(result.getResponse().getEvents()).isEmpty();
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
