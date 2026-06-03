package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.fasterxml.jackson.databind.JsonNode;
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
