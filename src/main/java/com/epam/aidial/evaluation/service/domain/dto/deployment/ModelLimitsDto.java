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
@Schema(description = "Model token limits")
public class ModelLimitsDto {

    @Schema(description = "Maximum total tokens")
    private Integer maxTotalTokens;

    @Schema(description = "Maximum completion tokens")
    private Integer maxCompletionTokens;
}
