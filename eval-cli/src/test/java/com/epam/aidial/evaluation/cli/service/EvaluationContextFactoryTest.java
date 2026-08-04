package com.epam.aidial.evaluation.cli.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
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

        final EvaluationContext context = factory.create(suite, 5, targetRef);

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
                DeploymentReferenceDto.builder().id("dep").name("dep").build());

        assertThat(context.getConcurrencyLevel()).isEqualTo(4);
        assertThat(context.getRequestTimeoutMs()).isEqualTo(3600000L);
        assertThat(context.getMaxRetries()).isEqualTo(3);
        assertThat(context.getToken()).isEqualTo("my-token");
        assertThat(context.getCreatedAtMs()).isEqualTo(1700000000000L);
        assertThat(context.getCancellationSignal()).isNotNull();
        assertThat(context.getCancellationSignal().get()).isFalse();
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
                suite, 1, DeploymentReferenceDto.builder().id("d").name("d").build());

        verify(targetProperties).getApiKey();
    }
}
