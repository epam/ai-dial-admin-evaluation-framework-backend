package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Structured validation warning (fieldName, path, message, code)")
public class ValidationWarningDto {

    @Schema(description = "Data field name that the warning relates to", example = "prompt")
    private String fieldName;

    @Schema(description = "JSONPath-like path from validator", example = "$.prompt")
    private String path;

    @Schema(description = "Human-readable message", example = "required property 'prompt' not found")
    private String message;

    @Schema(description = "Stable code for FE display or i18n")
    private ValidationWarningCode code;
}
