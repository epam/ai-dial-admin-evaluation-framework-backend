package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Application route upstream")
public class RouteUpstreamDto {

    @Schema(description = "Upstream endpoint URL")
    private String endpoint;

    @Schema(description = "Extra data")
    private Object extraData;

    @Schema(description = "Weight for load balancing")
    private Integer weight;

    @Schema(description = "Tier for routing priority")
    private Integer tier;
}
