package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolReferenceDto {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private Map<String, Object> inputSchema;

    private Map<String, Object> outputSchema;
}
