package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterDefinitionDto {

    @NotBlank
    @Size(max = 255)
    private String name;

    @NotNull
    private ParameterLocation in;

    private boolean required;

    private Map<String, Object> schema;
}
