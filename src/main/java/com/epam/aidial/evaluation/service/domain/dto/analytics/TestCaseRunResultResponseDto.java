package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.runner.dto.analytics.ExtractionWarningDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseRunResultResponseDto {

    private UUID id;
    private UUID testSuiteRunId;
    private UUID testSuiteId;
    private UUID testCaseId;
    private String testCaseName;
    private int runIndex;

    @Schema(description = "0-based turn position within a multi-turn test case (0 for single-turn).", example = "0")
    private int turnIndex;

    @Schema(description = "Total turn count of the test case (1 for single-turn).", example = "1")
    private int totalTurns;

    private JsonNode testCaseData;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode requestBody;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode responseBody;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer responseStatusCode;

    private ExecutionInfoResponseDto executionInfo;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonNode extractedColumns;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ExtractionWarningDto> extractionWarnings;

    private Long createdAt;
}
