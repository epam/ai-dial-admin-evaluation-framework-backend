package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RouteReferenceDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.model.suite.HttpSuite;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.util.CredentialKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

@ExtendWith(MockitoExtension.class)
class EvaluationContextFactoryTest {

    @Mock
    private EvalCliProperties cliProperties;

    @Mock
    private EvalCliProperties.Run runConfig;

    @Mock
    private TargetProperties targetProperties;

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC);

    private EvaluationContextFactory factory;

    @BeforeEach
    void setUp() {
        factory = new EvaluationContextFactory(cliProperties, targetProperties, clock, new RunExecutorFactory(true));

        when(cliProperties.getRun()).thenReturn(runConfig);
        when(runConfig.getConcurrencyLevel()).thenReturn(4);
        when(runConfig.getRequestTimeoutMs()).thenReturn(3600000L);
        when(runConfig.getMaxRetries()).thenReturn(3);
        when(runConfig.getRetryDelayMs()).thenReturn(1000L);
        when(runConfig.getRetryBackoffMultiplier()).thenReturn(2.0);
        when(runConfig.getMaxRetryDelayMs()).thenReturn(30000L);
        when(runConfig.getResultBatchSize()).thenReturn(50);
        when(runConfig.getMaxResponseSizeBytes()).thenReturn(10485760L);
        when(runConfig.getRateLimitRps()).thenReturn(null);
        when(targetProperties.getApiKey()).thenReturn("my-token");
    }

    @Test
    @DisplayName("creates context with target deployment ref override applied")
    void createsContextWithTargetDeploymentRefOverride() {
        final UUID suiteId = UUID.randomUUID();
        final UUID datasetId = UUID.randomUUID();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(suiteId)
                .datasetId(datasetId)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("source-model")
                        .name("Source Model")
                        .build())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final DeploymentReferenceDto targetRef = DeploymentReferenceDto.builder()
                .id("target-model")
                .name("Target Model")
                .build();

        final EvaluationContext context = factory.create(suite, 5, targetRef, null);

        // The --deployment-id override becomes the effective target the shared engine validates the
        // resolved request "model" against — the source suite's own ref is deliberately discarded.
        assertThat(context.getTarget()).isEqualTo(new DeploymentTarget(targetRef));
    }

    @Test
    @DisplayName("the fetched suite's recorded deployment ref becomes the effective target without an override")
    void createsContextWithSuiteRecordedDeploymentRefWhenNoOverride() {
        final DeploymentReferenceDto suiteRef = DeploymentReferenceDto.builder()
                .id("suite-model")
                .name("Suite Model")
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .deploymentRef(suiteRef)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        // RunOrchestrationService resolves the absent --deployment-id to the suite's recorded ref
        // (see RunOrchestrationServiceTest#fallsBackToSuiteDeploymentRefWhenTargetRefAbsent) and hands
        // it here as the target, so the engine validates against "suite-model".
        final EvaluationContext context = factory.create(suite, 1, suiteRef, null);

        assertThat(context.getTarget()).isEqualTo(new DeploymentTarget(suiteRef));
    }

    @Test
    @DisplayName("creates context with correct execution settings from CLI config")
    void createsContextWithExecutionSettingsFromConfig() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(
                suite,
                10,
                DeploymentReferenceDto.builder().id("dep").name("dep").build(),
                null);

        assertThat(context.getConcurrencyLevel()).isEqualTo(4);
        assertThat(context.getRequestTimeoutMs()).isEqualTo(3600000L);
        assertThat(context.getMaxRetries()).isEqualTo(3);
        assertThat(context.getCredential().value()).isEqualTo("my-token");
        assertThat(context.getCredential().kind()).isEqualTo(CredentialKind.API_KEY);
        assertThat(context.getCreatedAtMs()).isEqualTo(1700000000000L);
        assertThat(context.getExecutor().isShutdown()).isFalse();

        final EvaluationContext secondContext = factory.create(
                suite,
                10,
                DeploymentReferenceDto.builder().id("dep").name("dep").build(),
                null);

        assertThat(secondContext.getExecutor())
                .as("each create(...) call must get its own executor, not a shared singleton")
                .isNotSameAs(context.getExecutor());
    }

    @Test
    @DisplayName("runs the context's executor on a platform thread when virtual threads are disabled")
    void executorRunsOnPlatformThreadWhenVirtualThreadsDisabled() throws Exception {
        final EvaluationContextFactory platformFactory =
                new EvaluationContextFactory(cliProperties, targetProperties, clock, new RunExecutorFactory(false));
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = platformFactory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);
        final ExecutorService executor = context.getExecutor();
        try {
            final Future<Boolean> isVirtual =
                    executor.submit(() -> Thread.currentThread().isVirtual());
            assertThat(isVirtual.get()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("propagates the suite's additionalRequests chain into the context")
    void propagatesAdditionalRequestsChain() {
        final RequestDefinitionDto second = RequestDefinitionDto.builder()
                .name("second")
                .endpointRef(
                        EndpointContractDto.builder().method(HttpMethod.POST).build())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/second").build())
                .build();
        final RequestDefinitionDto third = RequestDefinitionDto.builder()
                .name("third")
                .endpointRef(
                        EndpointContractDto.builder().method(HttpMethod.GET).build())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/third").build())
                .build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .additionalRequests(List.of(second, third))
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        final List<RequestExecutionSpec> chain = ((HttpSuite) context.getSuite()).chain();
        assertThat(chain).hasSize(3);
        assertThat(chain.subList(1, 3))
                .extracting(
                        RequestExecutionSpec::name,
                        RequestExecutionSpec::endpointRef,
                        RequestExecutionSpec::requestTemplate)
                .containsExactly(
                        tuple(second.getName(), second.getEndpointRef(), second.getRequestTemplate()),
                        tuple(third.getName(), third.getEndpointRef(), third.getRequestTemplate()));
    }

    @Test
    @DisplayName("carries the suite's requestName as request #0's label")
    void carriesRequestName() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .requestName("primary")
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        assertThat(((HttpSuite) context.getSuite()).first().name()).isEqualTo("primary");
    }

    @Test
    @DisplayName("a null additionalRequests yields a single-request chain")
    void nullAdditionalRequestsYieldsSingleRequestChain() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        assertThat(((HttpSuite) context.getSuite()).chain()).hasSize(1);
    }

    @Test
    @DisplayName("token is sourced from TargetProperties")
    void tokenIsSourcedFromTargetProperties() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        verify(targetProperties).getApiKey();
    }

    @Test
    @DisplayName("carries the fetched dataset schema, including perTurn flags, as snapshotTestCaseSchema")
    void carriesFetchedSchemaWithPerTurnFlags() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();
        final FieldDefinitionDto sharedField = FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .build();
        final FieldDefinitionDto perTurnField = FieldDefinitionDto.builder()
                .name("turnPrompt")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();

        final EvaluationContext context = factory.create(
                suite,
                1,
                DeploymentReferenceDto.builder().id("d").name("d").build(),
                List.of(sharedField, perTurnField));

        assertThat(context.getSnapshotTestCaseSchema()).containsExactly(sharedField, perTurnField);
        assertThat(context.getSnapshotTestCaseSchema().get(1).getPerTurn()).isTrue();
    }

    @Test
    @DisplayName("a null fetched schema (legacy bundle) yields a null snapshotTestCaseSchema")
    void nullSchemaYieldsNullSnapshotTestCaseSchema() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        assertThat(context.getSnapshotTestCaseSchema()).isNull();
    }

    @Test
    @DisplayName("a ROUTE suite yields a RouteTarget carrying the suite's routesRef, ignoring the deployment override")
    void routeSuiteYieldsRouteTarget() {
        final RouteReferenceDto routesRef =
                RouteReferenceDto.builder().name("routes/platform/r1").build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .suiteType(SuiteType.ROUTE)
                .routesRef(routesRef)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        assertThat(context.getTarget()).isEqualTo(new RouteTarget(routesRef));
    }

    @Test
    @DisplayName("an MCP_TOOL suite yields a McpToolTarget carrying the suite's MCP deployment and tool refs")
    void mcpSuiteYieldsMcpToolTarget() {
        final McpDeploymentReferenceDto mcpRef =
                McpDeploymentReferenceDto.builder().id("toolset").build();
        final ToolReferenceDto toolRef =
                ToolReferenceDto.builder().name("search").build();
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(mcpRef)
                .toolRef(toolRef)
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(suite, 1, null, null);

        assertThat(context.getTarget()).isEqualTo(new McpToolTarget(mcpRef, toolRef));
    }
}
