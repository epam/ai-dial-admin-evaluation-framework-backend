package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotResponseDto;
import java.util.UUID;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {JacksonMapper.class})
public abstract class RunMetricSnapshotMapper {

    @Autowired
    protected JsonbMapper jsonbMapper;

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(source = "item.tsmdId", target = "tsmdId")
    @Mapping(source = "item.tsmdName", target = "tsmdName")
    @Mapping(source = "item.metricDeclarationId", target = "metricDeclarationId")
    @Mapping(source = "item.metricDeclarationVersionId", target = "metricDeclarationVersionId")
    @Mapping(source = "item.configBindings", target = "configBindings")
    @Mapping(source = "item.inputBindings", target = "inputBindings")
    @Mapping(source = "item.outputSchema", target = "outputSchema")
    @Mapping(source = "computationId", target = "computationId")
    @Mapping(source = "testSuiteRunId", target = "testSuiteRunId")
    @Mapping(source = "computedAtMs", target = "computedAtMs")
    public abstract RunMetricSnapshot toEntity(
            RunMetricSnapshotBatchWriteItemDto item, UUID computationId, UUID testSuiteRunId, long computedAtMs);

    @AfterMapping
    protected void defaultNullFields(@MappingTarget RunMetricSnapshot entity) {
        if (entity.getConfigBindings() == null) {
            entity.setConfigBindings("[]");
        }
        if (entity.getInputBindings() == null) {
            entity.setInputBindings("[]");
        }
        if (entity.getOutputSchema() == null) {
            entity.setOutputSchema("{}");
        }
    }

    @Mapping(target = "outputSchema", ignore = true)
    public abstract RunMetricSnapshotResponseDto toDto(RunMetricSnapshot entity);

    @AfterMapping
    protected void mapOutputSchema(RunMetricSnapshot entity, @MappingTarget RunMetricSnapshotResponseDto dto) {
        dto.setOutputSchema(jsonbMapper.mapJsonSchema(entity.getOutputSchema()));
    }
}
