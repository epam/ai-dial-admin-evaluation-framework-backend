package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Test-case pass-rate breakdown for a suite's most recent runs.
 *
 * <p>{@code runs} carries newest-first, in the same order as {@code test_suite_runs.created_at_ms DESC,
 * id DESC}; a run with no eval summaries is simply absent (see {@link RunPassRateDto}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Test-case pass-rate breakdown for a suite's most recent runs")
public class SuitePassRateResponseDto {

    @Schema(description = "The suite these runs belong to", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID testSuiteId;

    @Schema(
            description = "Newest-first (created_at_ms DESC, id DESC). Runs with no eval summaries are "
                    + "omitted, so this list may be shorter than the requested lastN.")
    private List<RunPassRateDto> runs;
}
