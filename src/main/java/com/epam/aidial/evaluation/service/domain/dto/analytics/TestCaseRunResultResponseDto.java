package com.epam.aidial.evaluation.service.domain.dto.analytics;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(
            description = "Id of the multi-turn this row belongs to; groups a multi-turn's per-turn rows. "
                    + "Omitted for single-turn results.",
            example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID multiTurnId;

    @Schema(
            description = "0-based multi-turn turn index this row belongs to. Single-turn results are 0.",
            example = "0")
    private int turnIndex;

    @Schema(
            description = "Total number of turns in the multi-turn. Single-turn results are 1; "
                    + "the last turn of a multi-turn is the row where turnIndex == totalTurns - 1.",
            example = "1")
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
