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
@Schema(description = "Model pricing (unit, prompt, completion)")
public class ModelPricingDto {

    @Schema(description = "Pricing unit (e.g. token)")
    private String unit;

    @Schema(description = "Prompt price")
    private String prompt;

    @Schema(description = "Completion price")
    private String completion;
}
