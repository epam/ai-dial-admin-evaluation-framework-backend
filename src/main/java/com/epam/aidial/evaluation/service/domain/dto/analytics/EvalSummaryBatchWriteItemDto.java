package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
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

    /**
     * 0-based conversation turn index. Optional: external single-turn callers may omit it, in which case it
     * defaults to {@code 0} at write time (a primitive {@code int} would send {@code 0} regardless and could
     * not be distinguished from "omitted", so this stays a nullable {@link Integer}).
     */
    @Min(value = 0, message = "turnIndex must be >= 0")
    private Integer turnIndex;

    /**
     * Conversation length (turn count). Optional: external single-turn callers may omit it, in which case it
     * defaults to {@code 1} at write time, matching the DB column default and keeping single-turn payloads
     * byte-compatible.
     */
    @Min(value = 0, message = "totalTurns must be >= 0")
    private Integer totalTurns;

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
