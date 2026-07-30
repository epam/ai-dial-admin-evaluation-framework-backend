package com.epam.aidial.evaluation.service.domain.dto;

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
public class MetricParameterBindingDto {

    @NotBlank(message = "Property is required")
    private String property;

    @NotNull(message = "Source is required")
    @Valid
    private MetricBindingSourceDto source;
}
