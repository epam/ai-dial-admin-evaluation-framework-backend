package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.DialCoreUrlBuilder;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import com.epam.aidial.evaluation.service.domain.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.ResolutionScope;
import com.epam.aidial.evaluation.service.domain.ResolvedRequestService;
import com.epam.aidial.evaluation.service.domain.ResponseColumnExtractor;
import com.epam.aidial.evaluation.service.domain.TemplateVariableExtractor;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@DisplayName("HttpChainStepExecutor")
class HttpChainStepExecutorTest {

    private final ObjectMapper objectMapper = JsonMapperConfiguration.createJsonMapper();

    private ResolvedRequestService resolvedRequestService;
    private DialCoreDeploymentInvoker deploymentInvoker;
    private DialCoreUrlBuilder urlBuilder;
    private RequestBodySerializerRegistry serializerRegistry;
    private ResponseColumnExtractor responseColumnExtractor;
    private HttpChainStepExecutor executor;

    @BeforeEach
    void setUp() {
        resolvedRequestService = mock(ResolvedRequestService.class);
        deploymentInvoker = mock(DialCoreDeploymentInvoker.class);
        urlBuilder = mock(DialCoreUrlBuilder.class);
        serializerRegistry = mock(RequestBodySerializerRegistry.class);

        EvaluationRunProperties runProperties = mock(EvaluationRunProperties.class);
        EvaluationRunProperties.Execution execution = mock(EvaluationRunProperties.Execution.class);
        when(runProperties.getExecution()).thenReturn(execution);
        when(execution.getHeaderBlacklist()).thenReturn(List.of("authorization"));

        responseColumnExtractor = mock(ResponseColumnExtractor.class);
        when(responseColumnExtractor.extract(anyList(), any()))
                .thenReturn(new ResponseColumnExtractor.ExtractionResult("{\"answer\":\"42\"}", "[]"));

        QuietJsonService jsonService = new QuietJsonService(objectMapper);
        executor = new HttpChainStepExecutor(
                resolvedRequestService,
                new TemplateVariableExtractor(),
                urlBuilder,
                serializerRegistry,
                responseColumnExtractor,
                runProperties,
                new DeploymentTurnInvoker(deploymentInvoker, jsonService),
                jsonService);
    }

    @Test
    @DisplayName("supports the HTTP chain request type")
    void supportsHttp() {
        assertThat(executor.supportedType()).isEqualTo(ChainRequestType.HTTP);
    }

    @Test
    @DisplayName("issues the call with the request's OWN endpointRef method and its resolved URL")
    void usesOwnEndpointRefAndUrl() {
        stubResolution("/session/close");
        stubStatus(200, Map.of("ok", true));
        when(urlBuilder.buildUrl("gpt-4", "/session/close")).thenReturn("/openai/deployments/gpt-4/session/close");

        ChainStepOutcome outcome = executor.execute(step(request(2, "teardown", HttpMethod.DELETE, "/session/close")));

        assertThat(outcome.isSuccess()).isTrue();
        verify(deploymentInvoker)
                .invokeWithStreaming(
                        eq(HttpMethod.DELETE), eq("/openai/deployments/gpt-4/session/close"), any(), any(), any());
    }

    @Test
    @DisplayName("resolves against a scope carrying both test-case data and the accumulated chain values")
    void resolvesAgainstAccumulatedScope() {
        stubResolution("/chat/completions");
        stubStatus(200, Map.of("ok", true));
        when(urlBuilder.buildUrl(any(), any())).thenReturn("/p");

        executor.execute(new ChainStepRequest(
                request(1, "invoke", HttpMethod.POST, "/chat/completions"),
                context(),
                Map.of("question", "q"),
                Map.of("session_id", "abc")));

        ArgumentCaptor<ResolutionScope> scopeCaptor = ArgumentCaptor.forClass(ResolutionScope.class);
        verify(resolvedRequestService).resolveInScope(any(), anyList(), scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().safeData()).containsEntry("question", "q");
        assertThat(scopeCaptor.getValue().safeResponseValues()).containsEntry("session_id", "abc");
    }

    @Test
    @DisplayName("a non-2xx after retries is reported as a non-SUCCESS outcome rather than thrown")
    void nonSuccessIsReportedNotThrown() {
        stubResolution("/p");
        stubStatus(500, Map.of("error", "boom"));
        when(urlBuilder.buildUrl(any(), any())).thenReturn("/p");

        ChainStepOutcome outcome = executor.execute(step(request(0, "a", HttpMethod.POST, "/p")));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.statusCode()).isEqualTo(500);
        assertThat(outcome.extractedValues()).isEmpty();
    }

    @Test
    @DisplayName("an unresolvable responseField with no declared default fails BEFORE any call is issued")
    void unresolvableDependencyShortCircuits() {
        RequestSpec spec = new RequestSpec(
                1,
                "invoke",
                ChainRequestType.HTTP,
                endpoint(HttpMethod.POST, "/p"),
                // No default declared on the placeholder, so the missing value cannot be substituted.
                RequestTemplateDto.builder()
                        .urlTemplate("/p")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("session", "${{session}}"))
                                .build())
                        .build(),
                List.of(InputBindingDto.builder()
                        .templateVariable("session")
                        .responseField("session_id")
                        .build()),
                List.of());

        ChainStepOutcome outcome = executor.execute(new ChainStepRequest(spec, context(), Map.of(), Map.of()));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.unresolvedResponseFields()).containsExactly("session_id");
        verify(deploymentInvoker, never()).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unresolvable responseField WITH a declared placeholder default still sends the request")
    void declaredDefaultAllowsTheCall() {
        RequestSpec spec = new RequestSpec(
                1,
                "invoke",
                ChainRequestType.HTTP,
                endpoint(HttpMethod.POST, "/p"),
                RequestTemplateDto.builder()
                        .urlTemplate("/p")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("session", "${{session|string:none}}"))
                                .build())
                        .build(),
                List.of(InputBindingDto.builder()
                        .templateVariable("session")
                        .responseField("session_id")
                        .build()),
                List.of());
        stubResolution("/p");
        stubStatus(200, Map.of("ok", true));
        when(urlBuilder.buildUrl(any(), any())).thenReturn("/p");

        ChainStepOutcome outcome = executor.execute(new ChainStepRequest(spec, context(), Map.of(), Map.of()));

        assertThat(outcome.unresolvedResponseFields()).isEmpty();
        assertThat(outcome.isSuccess()).isTrue();
        verify(deploymentInvoker).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a resolvable responseField present in the accumulated map does not short-circuit")
    void presentValueDoesNotShortCircuit() {
        RequestSpec spec = new RequestSpec(
                1,
                "invoke",
                ChainRequestType.HTTP,
                endpoint(HttpMethod.POST, "/p"),
                RequestTemplateDto.builder().urlTemplate("/p").build(),
                List.of(InputBindingDto.builder()
                        .templateVariable("session")
                        .responseField("session_id")
                        .build()),
                List.of());
        stubResolution("/p");
        stubStatus(200, Map.of("ok", true));
        when(urlBuilder.buildUrl(any(), any())).thenReturn("/p");

        ChainStepOutcome outcome =
                executor.execute(new ChainStepRequest(spec, context(), Map.of(), Map.of("session_id", "abc")));

        assertThat(outcome.unresolvedResponseFields()).isEmpty();
        assertThat(outcome.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a responseField present but NULL in the accumulated map fails before any call is issued")
    void nullValuedResponseFieldShortCircuits() {
        // ResponseColumnExtractor records a column whose JSONata matched nothing as an explicit JSON null, so
        // the key IS present in the accumulated map. TemplateVariableResolver requires a non-null value, so a
        // key-presence check here would let the chain fire request 1 with an unresolved placeholder — exactly
        // the semantically nonsense call this pre-flight check exists to prevent.
        RequestSpec spec = new RequestSpec(
                1,
                "invoke",
                ChainRequestType.HTTP,
                endpoint(HttpMethod.POST, "/p"),
                RequestTemplateDto.builder()
                        .urlTemplate("/p")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("session", "${{session}}"))
                                .build())
                        .build(),
                List.of(InputBindingDto.builder()
                        .templateVariable("session")
                        .responseField("session_id")
                        .build()),
                List.of());

        ChainStepOutcome outcome = executor.execute(
                new ChainStepRequest(spec, context(), Map.of(), Collections.singletonMap("session_id", null)));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.unresolvedResponseFields()).containsExactly("session_id");
        assertThat(outcome.issued()).isFalse();
        verify(deploymentInvoker, never()).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a null-valued responseField WITH a declared default still sends the request")
    void nullValuedResponseFieldWithDefaultProceeds() {
        RequestSpec spec = new RequestSpec(
                1,
                "invoke",
                ChainRequestType.HTTP,
                endpoint(HttpMethod.POST, "/p"),
                RequestTemplateDto.builder()
                        .urlTemplate("/p")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("session", "${{session|string:none}}"))
                                .build())
                        .build(),
                List.of(InputBindingDto.builder()
                        .templateVariable("session")
                        .responseField("session_id")
                        .build()),
                List.of());
        stubResolution("/p");
        stubStatus(200, Map.of("ok", true));
        when(urlBuilder.buildUrl(any(), any())).thenReturn("/p");

        ChainStepOutcome outcome = executor.execute(
                new ChainStepRequest(spec, context(), Map.of(), Collections.singletonMap("session_id", null)));

        assertThat(outcome.unresolvedResponseFields()).isEmpty();
        assertThat(outcome.isSuccess()).isTrue();
        verify(deploymentInvoker).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    // ---- harness ----

    private void stubResolution(String url) {
        when(resolvedRequestService.resolveInScope(any(), anyList(), any()))
                .thenReturn(ResolvedRequestDto.builder().url(url).build());
    }

    private void stubStatus(int statusCode, Object body) {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> new DeploymentInvocationResult(statusCode, false, body, null, new HttpHeaders()));
    }

    private static ChainStepRequest step(RequestSpec spec) {
        return new ChainStepRequest(spec, context(), Map.of(), Map.of());
    }

    private static RequestSpec request(int index, String label, HttpMethod method, String path) {
        return new RequestSpec(
                index,
                label,
                ChainRequestType.HTTP,
                endpoint(method, path),
                RequestTemplateDto.builder().urlTemplate(path).build(),
                List.of(),
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("ok")
                        .build()));
    }

    private static EndpointContractDto endpoint(HttpMethod method, String path) {
        return EndpointContractDto.builder()
                .method(method)
                .relativeUrlPattern(path)
                .build();
    }

    private static EvaluationContext context() {
        return EvaluationContext.builder()
                .snapshotDeploymentRef(
                        DeploymentReferenceDto.builder().id("gpt-4").build())
                .maxRetries(0)
                .retryDelayMs(1)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(1)
                .maxResponseSizeBytes(1_000_000)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }
}
