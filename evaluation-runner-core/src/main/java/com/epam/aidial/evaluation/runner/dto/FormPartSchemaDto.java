package com.epam.aidial.evaluation.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormPartSchemaDto {

    @NotBlank
    private String name;

    @NotNull
    private FormPartType type;

    private boolean required;

    private Map<String, Object> schema;

    private List<String> allowedContentTypes;

    private Long maxSizeBytes;
}
