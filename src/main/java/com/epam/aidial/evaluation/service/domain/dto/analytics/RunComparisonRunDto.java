package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One run's side of a comparison. Every value describes this run, never the run it was compared against. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One run's matched-row counts and recomputed metric scores")
public class RunComparisonRunDto {

    @Schema(description = "The compared run", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID runId;

    @Schema(
            description = "The run's latest computation, used for both matching and aggregation",
            example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private UUID computationId;

    @Schema(description = "All of the run's eval-summary rows for this computation", example = "400")
    private Long totalRowCount;

    @Schema(
            description = "Rows whose match key also occurs in the other run. May differ between the two runs: "
                    + "where a run holds several rows for one key, all of them match.",
            example = "350")
    private Long matchedRowCount;

    @Schema(
            description = "Matched rows with a SUCCESS execution status, for rendering a per-run success ratio "
                    + "over matchedRowCount. A row counts only if the test case executed AND every metric "
                    + "evaluated cleanly, so this is not any statistic's sample size — a metric's denominator "
                    + "may exceed it.",
            example = "338")
    private Long matchedSuccessRowCount;

    @Schema(
            description = "Mean exec_duration_ms over ALL matched rows, so its denominator is matchedRowCount. "
                    + "Failure rows are included. Absent when no row matched.",
            example = "1240.5")
    private Double avgExecDurationMs;

    @Schema(
            description = "Ids of this run's rows that did NOT match. Exclude them — filter "
                    + "not(id in [...]) — to reproduce the compared population in a follow-up query. Empty "
                    + "means every row matched, so no filter is needed.")
    private List<UUID> unmatchedEvalSummaryIds;

    @Schema(
            description = "Statistics recomputed over the matched rows only. A null aggregate is omitted, so no "
                    + "entry carries a null value. The two runs' arrays may legitimately differ in content.")
    private List<MetricScoreValueDto> scores;
}
