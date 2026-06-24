package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v1/analytics/eval-summaries/export.csv}.
 *
 * <p>Body-based delivery is driven by payload size: a wide run's {@code columns} list can blow past
 * URL-length limits. There is intentionally no {@code detailed} field — inclusion of
 * {@code requestBody} / {@code responseBody} is governed solely by their presence in {@code columns}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "EvalSummary CSV export request payload")
public class EvalSummaryExportRequestDto {

    @NotNull(message = "runId is required")
    @Schema(
            description = "TestSuiteRun identifier to export",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID runId;

    @Schema(
            description = "Computation identifier — either a UUID or the literal \"latest\" (default)",
            example = "latest")
    private String computation;

    @Size(
            max = ValidationConstants.MAX_EXPORT_COLUMNS,
            message = "columns must not exceed " + ValidationConstants.MAX_EXPORT_COLUMNS + " entries")
    @Schema(
            description = "Ordered subset of columns to emit. Empty/omitted = full manifest minus "
                    + "`requestBody`/`responseBody`. Naming either body column explicitly turns on the "
                    + "test_case_run_results JOIN projection. Discover the full manifest via GET /export/preview.",
            example =
                    "[\"testCaseName\", \"data::prompt\", \"metric::Accuracy::score\", \"metricInfo::Accuracy::score\", \"metricError::Accuracy\"]")
    private List<String> columns;

    @Size(
            max = ValidationConstants.MAX_LIST_FILTER_PARAMS,
            message = "filter must not exceed " + ValidationConstants.MAX_LIST_FILTER_PARAMS + " entries")
    @Schema(
            description =
                    "Filter tokens (`field:operator:value`, repeatable) evaluated against "
                            + "`FilterWhitelists.EVAL_SUMMARIES`. Multiple filters combine with AND. Body-supplied "
                            + "entries are taken verbatim — no comma-splitting.\n\n"
                            + "| Field | Type | Operators |\n"
                            + "|-------|------|-----------|\n"
                            + "| suiteId | uuid | eq, in |\n"
                            + "| runId | uuid | eq, in |\n"
                            + "| testCaseId | uuid | eq, in |\n"
                            + "| testCaseName | string | eq, ne, co, in |\n"
                            + "| executionStatus | string | eq, ne, in |\n"
                            + "| runIndex | integer | eq, gt, gte, lt, lte |\n"
                            + "| execDurationMs | integer | gt, gte, lt, lte |\n"
                            + "| responseStatusCode | integer | eq, gt, gte, lt, lte |\n"
                            + "| testCaseData | jsonb string | eq, ne, co |\n"
                            + "| metricValues | jsonb numeric (two-level path: metricName.outputName) | eq, ne, gt, gte, lt, lte |",
            example = "[\"executionStatus:eq:SUCCESS\"]")
    private List<String> filter;

    @Schema(description = "Single-ASCII-character CSV delimiter; null/omitted/empty defaults to ','.", example = ",")
    private String delimiter;
}
