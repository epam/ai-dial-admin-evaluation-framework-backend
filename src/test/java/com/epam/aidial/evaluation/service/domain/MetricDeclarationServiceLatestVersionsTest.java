package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.runner.util.RunnerJsonbMapper;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
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
    @DisplayName("returns one DTO per declaration with schemas mapped as JSON objects")
    void returnsOneDtoPerDeclarationWithSchemasAsObjects() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(
                        version(ACCURACY_ID, 3, "{\"type\":\"object\",\"properties\":{\"threshold\":{}}}", "{}"),
                        version(LATENCY_ID, 1, "{}", "{\"type\":\"object\"}")));

        List<MetricDeclarationVersionResponseDto> result = service.getLatestVersions();

        assertThat(result)
                .extracting(
                        MetricDeclarationVersionResponseDto::getMetricDeclarationId,
                        MetricDeclarationVersionResponseDto::getSchemaVersion)
                .containsExactly(tuple(ACCURACY_ID, 3), tuple(LATENCY_ID, 1));
        assertThat(result.get(0).getConfigSchema()).containsEntry("type", "object");
        assertThat(result.get(0).getOutputSchema()).isEmpty();
        assertThat(result.get(1).getConfigSchema()).isEmpty();
        assertThat(result.get(1).getOutputSchema()).containsEntry("type", "object");
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
                .thenReturn(List.of(version(LATENCY_ID, 1, "{}", "{}"), version(ACCURACY_ID, 2, "{}", "{}")));

        List<MetricDeclarationVersionResponseDto> result = service.getLatestVersions();

        assertThat(result)
                .extracting(MetricDeclarationVersionResponseDto::getMetricDeclarationId)
                .containsExactly(LATENCY_ID, ACCURACY_ID);
    }

    @Test
    @DisplayName("resolves all latest versions with one repository query instead of one per declaration")
    void usesSingleRepositoryQuery() {
        when(metricDeclarationVersionRepository.findLatestPerMetricDeclaration())
                .thenReturn(List.of(version(ACCURACY_ID, 1, "{}", "{}"), version(LATENCY_ID, 1, "{}", "{}")));

        service.getLatestVersions();

        verify(metricDeclarationVersionRepository).findLatestPerMetricDeclaration();
        verify(metricDeclarationVersionRepository, never()).findLatestByMetricDeclarationId(any());
    }

    private static MetricDeclarationVersion version(
            UUID declarationId, int schemaVersion, String configSchema, String outputSchema) {
        return MetricDeclarationVersion.builder()
                .id(UUID.randomUUID())
                .metricDeclarationId(declarationId)
                .schemaVersion(schemaVersion)
                .configSchema(configSchema)
                .inputSchema("{}")
                .outputSchema(outputSchema)
                .description("description")
                .createdAt(1704067200000L)
                .build();
    }
}
