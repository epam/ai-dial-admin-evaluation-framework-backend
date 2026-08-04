package com.epam.aidial.evaluation.cli.client.source.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code TestSuiteRunResponseDto} (minimal fields needed by the CLI).
 *
 * <p>Manually kept in sync with
 * {@code com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteRunResponseDto {

    private UUID id;
    private UUID testSuiteId;
    private String testRunName;
    private String status;
    private Long startedAt;
    private Long completedAt;
    private String errorMessage;
    private Long createdAt;
    private Long updatedAt;
}
