package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationWithLatestVersion;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationWithLatestVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.MetricDeclarationMapper;
import com.epam.aidial.evaluation.service.domain.mapper.MetricDeclarationVersionMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricDeclarationService.getLatestVersions")
class MetricDeclarationServiceLatestVersionsTest {

    private static final UUID ACCURACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LATENCY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private MetricDeclarationRepository metricDeclarationRepository;

    @Mock
    private MetricDeclarationVersionRepository metricDeclarationVersionRepository;

    @Mock
    private MetricDeclarationMapper metricDeclarationMapper;

    @Mock
    private SortParser sortParser;

    @Mock
    private FilterParser filterParser;

    private MetricDeclarationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper, new RunnerJsonbMapper(objectMapper));
        service = new MetricDeclarationService(
                metricDeclarationRepository,
                metricDeclarationVersionRepository,
                metricDeclarationMapper,
                new MetricDeclarationVersionMapper(jsonbMapper),
                sortParser,
                filterParser);
    }

    @Test
    @DisplayName("returns one item per declaration, carrying the declaration's own identity fields")
    void returnsOneItemPerDeclarationWithDeclarationFields() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(
                        withLatest(ACCURACY_ID, "Accuracy", 3, "{}", "{}"),
                        withLatest(LATENCY_ID, "Latency", 1, "{}", "{}")));

        List<MetricDeclarationWithLatestVersionResponseDto> result = service.getLatestVersions();

        assertThat(result)
                .extracting(
                        MetricDeclarationWithLatestVersionResponseDto::getId,
                        MetricDeclarationWithLatestVersionResponseDto::getProviderId,
                        MetricDeclarationWithLatestVersionResponseDto::getName)
                .containsExactly(
                        tuple(ACCURACY_ID, "test-provider", "Accuracy"), tuple(LATENCY_ID, "test-provider", "Latency"));
        assertThat(result)
                .extracting(dto -> dto.getLatestVersion().getSchemaVersion())
                .containsExactly(3, 1);
        assertThat(result.getFirst().getDescription()).isEqualTo("Accuracy declaration");
        assertThat(result.getFirst().getCreatedAt()).isEqualTo(1_704_067_200_000L);
    }

    @Test
    @DisplayName("nests the version with its schemas mapped as JSON objects")
    void nestsVersionWithSchemasAsObjects() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(withLatest(
                        ACCURACY_ID,
                        "Accuracy",
                        3,
                        "{\"type\":\"object\",\"properties\":{\"threshold\":{}}}",
                        "{\"type\":\"object\"}")));

        List<MetricDeclarationWithLatestVersionResponseDto> result = service.getLatestVersions();

        var version = result.getFirst().getLatestVersion();
        assertThat(version.getMetricDeclarationId()).isEqualTo(ACCURACY_ID);
        assertThat(version.getConfigSchema()).containsEntry("type", "object");
        assertThat(version.getInputSchema()).isEmpty();
        assertThat(version.getOutputSchema()).containsEntry("type", "object");
        assertThat(version.getDescription()).isEqualTo("Accuracy version 3");
    }

    @Test
    @DisplayName("returns an empty list when no declaration has a version")
    void returnsEmptyListWhenNoVersions() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of());

        assertThat(service.getLatestVersions()).isEmpty();
    }

    @Test
    @DisplayName("preserves the repository result order")
    void preservesRepositoryOrder() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(
                        withLatest(LATENCY_ID, "Latency", 1, "{}", "{}"),
                        withLatest(ACCURACY_ID, "Accuracy", 2, "{}", "{}")));

        List<MetricDeclarationWithLatestVersionResponseDto> result = service.getLatestVersions();

        assertThat(result)
                .extracting(MetricDeclarationWithLatestVersionResponseDto::getId)
                .containsExactly(LATENCY_ID, ACCURACY_ID);
    }

    @Test
    @DisplayName("resolves declarations and versions with one repository query, not one query per declaration")
    void usesSingleRepositoryQuery() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(
                        withLatest(ACCURACY_ID, "Accuracy", 1, "{}", "{}"),
                        withLatest(LATENCY_ID, "Latency", 1, "{}", "{}")));

        service.getLatestVersions();

        verify(metricDeclarationVersionRepository).findLatestPerMetricDeclaration();
        verify(metricDeclarationVersionRepository, never()).findLatestByMetricDeclarationId(any());
        verifyNoInteractions(metricDeclarationRepository);
    }

    private static MetricDeclarationWithLatestVersion withLatest(
            UUID declarationId, String name, int schemaVersion, String configSchema, String outputSchema) {
        MetricDeclaration declaration = MetricDeclaration.builder()
                .id(declarationId)
                .providerId("test-provider")
                .name(name)
                .displayName(name + " display")
                .description(name + " declaration")
                .createdAt(1_704_067_200_000L)
                .build();
        MetricDeclarationVersion version = MetricDeclarationVersion.builder()
                .id(UUID.randomUUID())
                .metricDeclarationId(declarationId)
                .schemaVersion(schemaVersion)
                .configSchema(configSchema)
                .inputSchema("{}")
                .outputSchema(outputSchema)
                .description(name + " version " + schemaVersion)
                .createdAt(1_704_067_300_000L)
                .build();
        return new MetricDeclarationWithLatestVersion(declaration, version);
    }
}
