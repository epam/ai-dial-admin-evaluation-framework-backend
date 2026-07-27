package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.service.domain.dto.AggregatedMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteMetricDefinitionMapper {

    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer validationWarningsSerializer;

    public TestSuiteMetricDefinitionResponseDto toDto(TestSuiteMetricDefinition entity) {
        if (entity == null) {
            return null;
        }
        List<MetricParameterBindingDto> configBindings = jsonbMapper.mapMetricBindings(entity.getConfigBindings());
        List<MetricParameterBindingDto> inputBindings = jsonbMapper.mapMetricBindings(entity.getInputBindings());
        List<ValidationWarningDto> warnings =
                validationWarningsSerializer.deserializeWarnings(entity.getValidationWarnings());

        return TestSuiteMetricDefinitionResponseDto.builder()
                .id(entity.getId())
                .testSuiteId(entity.getTestSuiteId())
                .metricDeclarationId(entity.getMetricDeclarationId())
                .metricDeclarationVersionId(entity.getMetricDeclarationVersionId())
                .name(entity.getName())
                .metricDeclarationName(entity.getMetricDeclarationName())
                .enabled(entity.isEnabled())
                .condition(entity.getCondition())
                .valid(entity.isValid())
                .validationWarnings(warnings)
                .configBindings(configBindings)
                .inputBindings(inputBindings)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public TestSuiteMetricDefinition toEntity(TestSuiteMetricDefinitionRequestDto dto, UUID testSuiteId) {
        if (dto == null) {
            return null;
        }
        return TestSuiteMetricDefinition.builder()
                .testSuiteId(testSuiteId)
                .metricDeclarationId(dto.getMetricDeclarationId())
                .metricDeclarationVersionId(dto.getMetricDeclarationVersionId())
                .name(dto.getName())
                .enabled(dto.isEnabled())
                .condition(dto.getCondition())
                .configBindings(jsonbMapper.mapMetricBindings(dto.getConfigBindings()))
                .inputBindings(jsonbMapper.mapMetricBindings(dto.getInputBindings()))
                .build();
    }

    public AggregatedMetricDefinitionResponseDto toAggregatedDto(AggregatedMetricDefinition entity) {
        if (entity == null) {
            return null;
        }
        List<MetricParameterBindingDto> configBindings = jsonbMapper.mapMetricBindings(entity.getConfigBindings());
        List<MetricParameterBindingDto> inputBindings = jsonbMapper.mapMetricBindings(entity.getInputBindings());

        var declaration = MetricDeclarationResponseDto.builder()
                .id(entity.getMetricDeclarationId())
                .providerId(entity.getDeclarationProviderId())
                .name(entity.getMetricDeclarationName())
                .description(entity.getDeclarationDescription())
                .createdAt(entity.getDeclarationCreatedAt())
                .build();

        var version = MetricDeclarationVersionResponseDto.builder()
                .id(entity.getVersionId())
                .metricDeclarationId(entity.getMetricDeclarationId())
                .schemaVersion(entity.getVersionSchemaVersion())
                .configSchema(jsonbMapper.mapJsonSchema(entity.getVersionConfigSchema()))
                .inputSchema(jsonbMapper.mapJsonSchema(entity.getVersionInputSchema()))
                .outputSchema(jsonbMapper.mapJsonSchema(entity.getVersionOutputSchema()))
                .description(entity.getVersionDescription())
                .createdAt(entity.getVersionCreatedAt())
                .build();

        return AggregatedMetricDefinitionResponseDto.builder()
                .id(entity.getId())
                .testSuiteId(entity.getTestSuiteId())
                .metricDeclarationId(entity.getMetricDeclarationId())
                .metricDeclarationVersionId(entity.getMetricDeclarationVersionId())
                .name(entity.getName())
                .metricDeclarationName(entity.getMetricDeclarationName())
                .configBindings(configBindings)
                .inputBindings(inputBindings)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .metricDeclaration(declaration)
                .metricDeclarationVersion(version)
                .build();
    }

    public void update(TestSuiteMetricDefinition entity, TestSuiteMetricDefinitionRequestDto dto) {
        if (entity == null || dto == null) {
            return;
        }
        entity.setName(dto.getName());
        entity.setMetricDeclarationId(dto.getMetricDeclarationId());
        entity.setMetricDeclarationVersionId(dto.getMetricDeclarationVersionId());
        entity.setEnabled(dto.isEnabled());
        entity.setCondition(dto.getCondition());
        entity.setConfigBindings(jsonbMapper.mapMetricBindings(dto.getConfigBindings()));
        entity.setInputBindings(jsonbMapper.mapMetricBindings(dto.getInputBindings()));
    }
}
