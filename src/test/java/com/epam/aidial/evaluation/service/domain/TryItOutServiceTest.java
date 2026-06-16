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
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.configuration.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.configuration.properties.dial.DialCoreProperties;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.TryItOutService.TryItOutValidationException;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.job.SseEvent;
import com.epam.aidial.evaluation.service.domain.job.SseEventParser;
import com.epam.aidial.evaluation.service.domain.job.SseParseResult;
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
                sseEventProcessingProperties);
        lenient()
                .when(serializerRegistry.serialize(any()))
                .thenReturn(new SerializedBody(MediaType.APPLICATION_JSON, Map.of("prompt", "Hello")));
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(executionProperties);
        lenient().when(executionProperties.getMaxResponseSizeBytes()).thenReturn(10 * 1024 * 1024L);
        lenient().when(dialCoreProperties.getTryOut()).thenReturn(tryOutProperties);
        lenient().when(tryOutProperties.getReadTimeoutMs()).thenReturn(30_000);
        lenient().when(sseEventProcessingProperties.getMaxTotalDurationMs()).thenReturn(3_600_000L);
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
            when(resolvedRequestService.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
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
            when(resolvedRequestService.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            Map<String, Object> variables = new HashMap<>();
            variables.put("prompt", "Hello");
            variables.put("nullVar", null);
            service.tryWithVariables(SUITE_ID, variables);

            ArgumentCaptor<List<InputBindingDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(resolvedRequestService).resolve(any(), captor.capture(), anyMap());
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
            when(resolvedRequestService.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
            when(urlBuilder.buildUrl(any(), any())).thenReturn("/path");
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(nonStreamingResult(200, null));

            Map<String, Object> variables = new HashMap<>();
            variables.put("", "value");
            variables.put("  ", "value2");
            variables.put("valid", "ok");
            service.tryWithVariables(SUITE_ID, variables);

            ArgumentCaptor<List<InputBindingDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(resolvedRequestService).resolve(any(), captor.capture(), anyMap());
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
            when(resolvedRequestService.resolve(any(), anyList(), anyMap())).thenReturn(resolved);
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
            when(resolvedRequestService.resolve(any(), anyList(), anyMap())).thenReturn(buildResolvedRequest());
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
