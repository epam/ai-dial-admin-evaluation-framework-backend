package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationWithLatestVersion;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationWithLatestVersionResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class MetricDeclarationVersionMapper {

    private final JsonbMapper jsonbMapper;

    public MetricDeclarationVersionResponseDto toDto(MetricDeclarationVersion version) {
        if (version == null) {
            return null;
        }
        return MetricDeclarationVersionResponseDto.builder()
                .id(version.getId())
                .metricDeclarationId(version.getMetricDeclarationId())
                .schemaVersion(version.getSchemaVersion())
                .configSchema(jsonbMapper.mapJsonSchema(version.getConfigSchema()))
                .inputSchema(jsonbMapper.mapJsonSchema(version.getInputSchema()))
                .outputSchema(jsonbMapper.mapJsonSchema(version.getOutputSchema()))
                .displayName(version.getDisplayName())
                .description(version.getDescription())
                .createdAt(version.getCreatedAt())
                .build();
    }

    /**
     * Flattens the declaration onto the item and nests its latest version, so a caller gets the
     * declaration's identity fields (providerId, name, ...) without a second request. Hand-written
     * rather than MapStruct: MapStruct's Spring component model injects collaborators as fields, which
     * would make this mapper non-constructible in the plain-Mockito service tests.
     */
    public MetricDeclarationWithLatestVersionResponseDto toDto(MetricDeclarationWithLatestVersion entity) {
        if (entity == null) {
            return null;
        }
        MetricDeclaration declaration = entity.declaration();
        return MetricDeclarationWithLatestVersionResponseDto.builder()
                .id(declaration.getId())
                .providerId(declaration.getProviderId())
                .name(declaration.getName())
                .displayName(declaration.getDisplayName())
                .description(declaration.getDescription())
                .createdAt(declaration.getCreatedAt())
                .latestVersion(toDto(entity.latestVersion()))
                .build();
    }
}
