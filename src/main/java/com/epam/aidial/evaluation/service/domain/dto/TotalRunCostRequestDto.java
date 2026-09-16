package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request body for a batch total-cost lookup.")
public class TotalRunCostRequestDto {

    @Schema(
            description = "Run ids to compute total cost for, in the order results should be returned. "
                    + "Must be non-empty, up to ValidationConstants.MAX_BATCH_RUN_IDS entries.")
    private List<UUID> runIds;
}
