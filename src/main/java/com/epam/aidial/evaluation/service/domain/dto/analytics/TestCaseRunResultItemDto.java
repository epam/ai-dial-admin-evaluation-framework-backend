package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
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
            description = "0-based chain position of the multi-request suite request that produced this row; "
                    + "defaults to 0 when omitted. Participates in the row's natural key, so two items sharing "
                    + "testCaseId/runIndex/turnIndex but differing here both persist. NOT bounded against any "
                    + "suite snapshot's chain length: this endpoint accepts results from EXTERNAL test suite "
                    + "runs, which have no snapshot chain to bound it against.",
            example = "0")
    @Min(value = 0, message = "requestIndex must be >= 0")
    private Integer requestIndex;

    @Schema(
            description = "Resolved label of the chain request that produced this row, taken verbatim from the "
                    + "payload. NOT derived from or cross-validated against a suite snapshot — an external run "
                    + "has no chain from which a label could be derived. Display-only and not part of the "
                    + "natural key, so a label inconsistent with a suite's configuration breaks nothing.",
            example = "invoke")
    @Size(max = 255, message = "requestLabel must not exceed 255 characters")
    private String requestLabel;

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
