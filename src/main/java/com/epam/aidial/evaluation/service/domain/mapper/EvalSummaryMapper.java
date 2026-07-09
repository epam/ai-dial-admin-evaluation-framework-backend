package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryResponseDto;
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
public abstract class EvalSummaryMapper {

    @Autowired
    protected GrafanaLinkBuilder grafanaLinkBuilder;

    @Mapping(target = "id", expression = "java(java.util.UUID.randomUUID())")
    @Mapping(source = "item.testCaseRunResultId", target = "testCaseRunResultId")
    @Mapping(source = "item.testCaseId", target = "testCaseId")
    @Mapping(source = "item.testCaseName", target = "testCaseName")
    @Mapping(source = "item.runIndex", target = "runIndex")
    // Turn fields are optional on the DTO (nullable Integer). External single-turn callers may omit them,
    // in which case they default to 0/1 — matching the DB column defaults and keeping single-turn payloads
    // byte-compatible.
    @Mapping(target = "turnIndex", expression = "java(item.getTurnIndex() == null ? 0 : item.getTurnIndex())")
    @Mapping(target = "totalTurns", expression = "java(item.getTotalTurns() == null ? 1 : item.getTotalTurns())")
    @Mapping(source = "item.testCaseData", target = "testCaseData")
    @Mapping(source = "item.extractedColumns", target = "extractedColumns")
    @Mapping(source = "item.executionStatus", target = "executionStatus")
    @Mapping(source = "item.execDurationMs", target = "execDurationMs")
    @Mapping(source = "item.responseStatusCode", target = "responseStatusCode")
    @Mapping(source = "item.metricValues", target = "metricValues")
    @Mapping(source = "item.metricInfos", target = "metricInfos")
    @Mapping(source = "item.extractionWarnings", target = "extractionWarnings")
    @Mapping(target = "requestBody", ignore = true)
    @Mapping(target = "responseBody", ignore = true)
    @Mapping(source = "computationId", target = "computationId")
    @Mapping(source = "testSuiteId", target = "testSuiteId")
    @Mapping(source = "testSuiteRunId", target = "testSuiteRunId")
    @Mapping(source = "createdAtMs", target = "createdAtMs")
    @Mapping(source = "computedAtMs", target = "computedAtMs")
    public abstract EvalSummary toEntity(
            EvalSummaryBatchWriteItemDto item,
            UUID testSuiteId,
            UUID testSuiteRunId,
            UUID computationId,
            long createdAtMs,
            long computedAtMs);

    @AfterMapping
    protected void defaultExtractedColumns(@MappingTarget EvalSummary entity) {
        if (entity.getExtractedColumns() == null) {
            entity.setExtractedColumns("{}");
        }
        if (entity.getExtractionWarnings() == null) {
            entity.setExtractionWarnings("[]");
        }
    }

    @Mapping(source = "createdAtMs", target = "createdAt")
    @Mapping(source = "computedAtMs", target = "computedAt")
    @Mapping(target = "executionStatus", expression = "java(entity.getExecutionStatus().name())")
    @Mapping(target = "grafanaTraceUrl", ignore = true)
    public abstract EvalSummaryResponseDto toDto(EvalSummary entity);

    @Mapping(source = "createdAtMs", target = "createdAt")
    @Mapping(source = "computedAtMs", target = "computedAt")
    @Mapping(target = "executionStatus", expression = "java(entity.getExecutionStatus().name())")
    @Mapping(source = "extractionWarnings", target = "extractionWarnings")
    @Mapping(source = "requestBody", target = "requestBody")
    @Mapping(source = "responseBody", target = "responseBody")
    @Mapping(target = "grafanaTraceUrl", ignore = true)
    public abstract EvalSummaryDetailResponseDto toDetailDto(EvalSummary entity);

    @AfterMapping
    protected void populateGrafanaTraceUrl(EvalSummary entity, @MappingTarget EvalSummaryResponseDto dto) {
        dto.setGrafanaTraceUrl(grafanaLinkBuilder.testCaseAggregateUrl(
                entity.getTestSuiteRunId(), entity.getTestCaseId(),
                entity.getCreatedAtMs(), entity.getComputedAtMs()));
    }

    @AfterMapping
    protected void populateGrafanaTraceUrl(EvalSummary entity, @MappingTarget EvalSummaryDetailResponseDto dto) {
        dto.setGrafanaTraceUrl(grafanaLinkBuilder.testCaseAggregateUrl(
                entity.getTestSuiteRunId(), entity.getTestCaseId(),
                entity.getCreatedAtMs(), entity.getComputedAtMs()));
    }
}
