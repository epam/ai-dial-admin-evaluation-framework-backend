package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseColumnDefinitionDto {

    @NotBlank
    @Size(max = 255)
    @Pattern(
            regexp = ValidationConstants.NAME_NO_TWO_COLON_PATTERN,
            message = ValidationConstants.NAME_NO_TWO_COLON_MESSAGE)
    private String name;

    @Size(max = 255)
    private String displayName;

    @NotBlank
    @Size(max = 2000)
    private String expression;

    /**
     * Optional display type. When null in the DTO, defaults to {@link SchemaFieldType#STRING}
     * in {@code TestSuiteService.normalizeRequest()} before persistence.
     */
    private SchemaFieldType type;
}
