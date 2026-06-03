package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseRunResultItemDto {

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

    private JsonNode requestBody;

    private JsonNode responseBody;

    private Integer responseStatusCode;

    @NotNull(message = "executionInfo is required")
    @Valid
    private ExecutionInfoRequestDto executionInfo;

    /**
     * Optional pre-populated extracted column values (keyed by column name).
     * When null, the persistence layer defaults to {@code {}}.
     */
    private JsonNode extractedColumns;

    /**
     * Optional pre-populated extraction warnings.
     * When null, the persistence layer defaults to {@code []}.
     */
    private List<ExtractionWarningDto> extractionWarnings;
}
