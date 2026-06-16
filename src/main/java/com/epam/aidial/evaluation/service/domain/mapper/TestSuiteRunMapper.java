package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.RunErrorDetailsDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunMapper {

    private final ObjectMapper objectMapper;
    private final GrafanaLinkBuilder grafanaLinkBuilder;

    public TestSuiteRunResponseDto toDto(TestSuiteRun entity) {
        if (entity == null) {
            return null;
        }
        return TestSuiteRunResponseDto.builder()
                .id(entity.getId())
                .testSuiteId(entity.getTestSuiteId())
                .testRunName(entity.getTestRunName())
                .status(entity.getStatus())
                .runConfig(deserializeRunConfig(entity.getRunConfig()))
                .numberOfTestCases(entity.getNumberOfTestCases())
                .suiteSnapshot(deserializeSuiteSnapshot(entity.getSuiteSnapshot()))
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .errorMessage(entity.getErrorMessage())
                .errorDetails(deserializeErrorDetails(entity.getErrorDetails()))
                .grafanaExploreUrl(grafanaLinkBuilder.runExploreUrl(
                        entity.getId(), entity.getStartedAt(), entity.getCompletedAt()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private RunConfigDto deserializeRunConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RunConfigDto.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize runConfig", ex);
        }
    }

    private RunErrorDetailsDto deserializeErrorDetails(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RunErrorDetailsDto.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize errorDetails", ex);
        }
    }

    private SuiteSnapshotDto deserializeSuiteSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SuiteSnapshotDto.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to deserialize suiteSnapshot", ex);
        }
    }
}
