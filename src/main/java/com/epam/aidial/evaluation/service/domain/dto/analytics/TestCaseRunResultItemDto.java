package com.epam.aidial.evaluation.service.domain.dto.analytics;

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
import tools.jackson.databind.JsonNode;

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

    /**
     * 0-based multi-turn turn index. Optional: external single-turn callers may omit it, in which case it
     * defaults to {@code 0} at write time (a primitive {@code int} would send {@code 0} regardless and could
     * not be distinguished from "omitted", so this stays a nullable {@link Integer}). Importing a multi-turn
     * A multi-turn requires distinct {@code turnIndex} values per row: it is part of the natural key, so
     * omitting it would collapse every turn onto the same key and silently drop all but one row.
     */
    @Min(value = 0, message = "turnIndex must be >= 0")
    private Integer turnIndex;

    /**
     * MultiTurn length (turn count). Optional: external single-turn callers may omit it, in which case it
     * defaults to {@code 1} at write time, matching the DB column default and keeping single-turn payloads
     * byte-compatible. The degenerate data-shape ERROR row is {@code 0/0}, so the lower bound is {@code 0}.
     */
    @Min(value = 0, message = "totalTurns must be >= 0")
    private Integer totalTurns;

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
