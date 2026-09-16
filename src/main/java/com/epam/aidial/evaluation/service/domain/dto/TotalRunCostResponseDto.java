package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One run's total cost from a batch lookup")
public class TotalRunCostResponseDto {

    @Schema(description = "The requested run", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID runId;

    @Schema(
            description = "Total cost of this run (both execution and metric-evaluation phases combined), in "
                    + "DIAL Core's pricing currency unit. Null when dial-adas has no matching usage-log rows "
                    + "for this run — including an unknown/nonexistent run id, which is indistinguishable from "
                    + "a real run with no usage.",
            example = "0.0007125")
    private Double totalCost;
}
