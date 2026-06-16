package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Model capabilities (scale types, completion types)")
public class ModelCapabilitiesDto {

    @Schema(description = "Scale types supported by the model")
    private List<String> scaleTypes;

    @Schema(description = "Whether completion is supported")
    private Boolean completion;

    @Schema(description = "Whether chat completion is supported")
    private Boolean chatCompletion;

    @Schema(description = "Whether embeddings are supported")
    private Boolean embeddings;

    @Schema(description = "Whether fine-tuning is supported")
    private Boolean fineTune;

    @Schema(description = "Whether inference is supported")
    private Boolean inference;
}
