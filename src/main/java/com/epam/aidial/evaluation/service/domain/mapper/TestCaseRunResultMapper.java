package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import java.util.UUID;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        uses = {JacksonMapper.class, ValidationWarningsSerializer.class})
public abstract class TestCaseRunResultMapper {

    @Autowired
    protected GrafanaLinkBuilder grafanaLinkBuilder;

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(source = "item.turnIndex", target = "turnIndex", defaultValue = "0")
    @Mapping(source = "item.totalTurns", target = "totalTurns", defaultValue = "1")
    @Mapping(source = "item.requestIndex", target = "requestIndex", defaultValue = "0")
    @Mapping(source = "item.requestLabel", target = "requestLabel")
    @Mapping(source = "item.executionInfo.status", target = "executionStatus")
    @Mapping(source = "item.executionInfo.startedAt", target = "execStartedAtMs")
    @Mapping(source = "item.executionInfo.completedAt", target = "execCompletedAtMs")
    @Mapping(source = "item.executionInfo.traceId", target = "traceId")
    @Mapping(source = "item.executionInfo.retryCount", target = "retryCount", defaultValue = "0")
    @Mapping(source = "item.executionInfo.logDetails", target = "logDetails", qualifiedByName = "serializeLogDetails")
    @Mapping(
            target = "execDurationMs",
            expression = "java(item.getExecutionInfo().getCompletedAt() - item.getExecutionInfo().getStartedAt())")
    public abstract TestCaseRunResult toEntity(
            TestCaseRunResultItemDto item, UUID testSuiteId, UUID testSuiteRunId, long createdAtMs);

    /**
     * Defaults null extracted-column fields after mapping from DTO to entity.
     * Null extractedColumns → "{}", null extractionWarnings → "[]".
     */
    @AfterMapping
    protected void defaultExtractedFields(@MappingTarget TestCaseRunResult entity) {
        if (entity.getExtractedColumns() == null) {
            entity.setExtractedColumns("{}");
        }
        if (entity.getExtractionWarnings() == null) {
            entity.setExtractionWarnings("[]");
        }
    }

    @Mapping(source = "executionStatus", target = "executionInfo.status")
    @Mapping(source = "execStartedAtMs", target = "executionInfo.startedAt")
    @Mapping(source = "execCompletedAtMs", target = "executionInfo.completedAt")
    @Mapping(source = "execDurationMs", target = "executionInfo.durationMs")
    @Mapping(source = "traceId", target = "executionInfo.traceId")
    @Mapping(source = "retryCount", target = "executionInfo.retryCount")
    @Mapping(source = "logDetails", target = "executionInfo.logDetails", qualifiedByName = "deserializeLogDetails")
    @Mapping(source = "createdAtMs", target = "createdAt")
    public abstract TestCaseRunResultResponseDto toDto(TestCaseRunResult entity);

    @AfterMapping
    protected void populateGrafanaTraceUrl(TestCaseRunResult entity, @MappingTarget TestCaseRunResultResponseDto dto) {
        if (dto.getExecutionInfo() != null && entity.getTraceId() != null) {
            dto.getExecutionInfo().setGrafanaTraceUrl(grafanaLinkBuilder.traceUrl(entity.getTraceId()));
        }
    }
}
