package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Maps a metric schema property to a data source")
public class MetricParameterBindingDto {

    @NotBlank(message = "Property is required")
    @Schema(description = "Top-level property name in the metric's config or input schema", example = "reference")
    private String property;

    @NotNull(message = "Source is required")
    @Valid
    @Schema(description = "The data source for this binding")
    private MetricBindingSourceDto source;
}
