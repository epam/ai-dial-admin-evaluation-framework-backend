package com.epam.aidial.evaluation.service.domain.dto.analytics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * One imported eval result: an already-produced model response for a test case, to be scored via
 * metric evaluation without re-invoking a deployment. Mirrors what a live Phase 1 invocation
 * actually produces — {@code testCaseId}/{@code testCaseName}/{@code testCaseData} are caller-supplied
 * and trusted as-is, never resolved against or validated by any existing {@code TestCase} row. This
 * also means results can be replayed into a cloned suite whose dataset has entirely different
 * test-case ids (see {@code testCaseId}). {@code extractedColumns}/{@code extractionWarnings} are
 * deliberately excluded — response columns are always extracted server-side from {@link #responseBody}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResultsImportItemDto {

    /** Preferred identifying label; falls back to {@link #testCaseName} when null. Not resolved against any dataset. */
    private UUID testCaseId;

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
}
