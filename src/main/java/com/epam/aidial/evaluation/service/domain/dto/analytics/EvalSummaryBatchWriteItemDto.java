package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class EvalSummaryBatchWriteItemDto {

    @NotNull(message = "testCaseRunResultId is required")
    private UUID testCaseRunResultId;

    @NotNull(message = "testCaseId is required")
    private UUID testCaseId;

    @NotBlank(message = "testCaseName is required")
    @Size(max = 255, message = "testCaseName must be less than 255 characters")
    private String testCaseName;

    @NotNull(message = "runIndex is required")
    @Min(value = 0, message = "runIndex must be >= 0")
    @Max(value = 99999, message = "runIndex must be <= 99999")
    private Integer runIndex;

    @Schema(
            description = "0-based turn position within a multi-turn test case; defaults to 0 (single-turn) "
                    + "when omitted.",
            example = "0")
    @Min(value = 0, message = "turnIndex must be >= 0")
    private Integer turnIndex;

    @Schema(
            description = "Planned turn count of the test case; defaults to 1 (single-turn) when omitted.",
            example = "1")
    @Min(value = 1, message = "totalTurns must be >= 1")
    private Integer totalTurns;

    @Schema(
            description = "0-based chain position of the multi-request suite request whose result row this "
                    + "summarizes; defaults to 0 when omitted. Part of the natural key. Not cross-validated "
                    + "against any suite snapshot, mirroring the turn fields.",
            example = "0")
    @Min(value = 0, message = "requestIndex must be >= 0")
    private Integer requestIndex;

    @Schema(description = "Resolved label of that chain request, taken verbatim. Display-only.", example = "invoke")
    @Size(max = 255, message = "requestLabel must not exceed 255 characters")
    private String requestLabel;

    @NotNull(message = "testCaseData is required")
    private JsonNode testCaseData;

    private JsonNode extractedColumns;

    @NotNull(message = "executionStatus is required")
    private ExecutionStatus executionStatus;

    @NotNull(message = "execDurationMs is required")
    private Long execDurationMs;

    private Integer responseStatusCode;

    @NotNull(message = "metricValues is required")
    private JsonNode metricValues;

    private JsonNode metricInfos;

    private JsonNode extractionWarnings;
}
