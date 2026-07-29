package com.epam.aidial.evaluation.service.domain.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * Two runs' metric scores, each recomputed over only the eval-summary rows the runs have in common.
 *
 * <p>An array rather than a map keyed by run id: a UUID-keyed map degrades to {@code additionalProperties} in
 * OpenAPI, losing the schema for what is in fact a fixed, well-typed object.
 */
@Data
@Builder
@Schema(description = "Metric scores for two runs, recomputed over their shared test cases")
public class RunComparisonResponseDto {

    @Schema(description = "One entry per requested run, in the order the run ids were given")
    private List<RunComparisonRunDto> runs;
}
