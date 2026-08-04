package com.epam.aidial.evaluation.cli.client.source.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code TestCaseResponseDto}.
 *
 * <p>Manually kept in sync with
 * {@code com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseResponseDto {

    private UUID id;
    private String testCaseName;
    private Map<String, Object> data;
    private List<Map<String, Object>> multiTurnData;
    private boolean valid;
    private Long createdAt;
    private Long updatedAt;
}
