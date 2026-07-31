package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
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
}
