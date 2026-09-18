package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One run's test-case pass-rate breakdown over its latest computation.
 *
 * <p>The counting unit is one eval-summary row (test case x run_index x request_index x turn_index). The
 * four counts partition {@code totalCount} by construction: every row is either {@code failedCount}
 * (execution did not succeed) or a {@code SUCCESS} row split across the remaining three buckets by verdict.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One run's test-case pass-rate breakdown over its latest computation")
public class RunPassRateDto {

    @Schema(description = "The run these counts belong to", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID testSuiteRunId;

    @Schema(
            description = "The run's latest computation (max computed_at_ms, ties broken by greater "
                    + "computation_id under text ordering) that these counts were aggregated over",
            example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private UUID computationId;

    @Schema(
            description = "The run's current status. A RUNNING run's counts are partial: Phase 2 flushes "
                    + "eval-summary rows in batches, so they grow across subsequent calls until the run "
                    + "reaches a terminal status.",
            example = "COMPLETED")
    private String status;

    @Schema(description = "The run's creation time, epoch milliseconds", example = "1737000000000")
    private Long runCreatedAtMs;

    @Schema(
            description = "Rows whose execution_status is not SUCCESS (FAILED, TIMEOUT, or ERROR). ERROR "
                    + "includes rows that executed but failed during metric evaluation.",
            example = "2")
    private long failedCount;

    @Schema(description = "SUCCESS rows with a test_case_eval_scores.passed value of true.", example = "38")
    private long successPassedCount;

    @Schema(description = "SUCCESS rows with a test_case_eval_scores.passed value of false.", example = "5")
    private long successNotPassedCount;

    @Schema(
            description = "SUCCESS rows with no verdict available, either because a test_case_eval_scores row "
                    + "exists but its passed value is NULL (no overallScoreThreshold configured on the suite "
                    + "snapshot, or the score itself is null), or because no score row was ever written for "
                    + "the row (the suite has no overallScore definition, the per-row score batch write "
                    + "failed and was swallowed, or a RUNNING run's flush hasn't reached this row yet).",
            example = "0")
    private long successNoVerdictCount;

    @Schema(
            description = "failedCount + successPassedCount + successNotPassedCount + successNoVerdictCount",
            example = "45")
    private long totalCount;
}
