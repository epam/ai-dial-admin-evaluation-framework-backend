package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.job.EvaluationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
        factory = new EvaluationContextFactory(cliProperties, targetProperties, clock);

        when(cliProperties.getRun()).thenReturn(runConfig);
        when(runConfig.getConcurrencyLevel()).thenReturn(4);
        when(runConfig.getRequestTimeoutMs()).thenReturn(3600000L);
        when(runConfig.getMaxRetries()).thenReturn(3);
        when(runConfig.getRetryDelayMs()).thenReturn(1000L);
        when(runConfig.getRetryBackoffMultiplier()).thenReturn(2.0);
        when(runConfig.getMaxRetryDelayMs()).thenReturn(30000L);
        when(runConfig.getResultBatchSize()).thenReturn(50);
        when(runConfig.getMaxResponseSizeBytes()).thenReturn(10485760L);
        when(runConfig.getCancellationGracePeriodMs()).thenReturn(30000L);
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
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final DeploymentReferenceDto targetRef = DeploymentReferenceDto.builder()
                .id("target-model")
                .name("Target Model")
                .build();

        final EvaluationContext context = factory.create(suite, 5, targetRef, null);

        // Target deployment ref is applied, not the source suite's ref
        assertThat(context.getSnapshotDeploymentRef()).isEqualTo(targetRef);
        assertThat(context.getSnapshotDeploymentRef().getId()).isEqualTo("target-model");
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
        assertThat(context.getToken()).isEqualTo("my-token");
        assertThat(context.getCreatedAtMs()).isEqualTo(1700000000000L);
        assertThat(context.getCancellationSignal()).isNotNull();
        assertThat(context.getCancellationSignal().get()).isFalse();
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

        assertThat(context.getSnapshotAdditionalRequests()).containsExactly(second, third);
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

        assertThat(context.getSnapshotRequestName()).isEqualTo("primary");
    }

    @Test
    @DisplayName("defaults a null additionalRequests to an empty list")
    void defaultsNullAdditionalRequestsToEmptyList() {
        final TestSuiteResponseDto suite = TestSuiteResponseDto.builder()
                .id(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .responseColumns(List.of())
                .inputBindings(List.of())
                .build();

        final EvaluationContext context = factory.create(
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build(), null);

        assertThat(context.getSnapshotAdditionalRequests()).isNotNull().isEmpty();
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
}
