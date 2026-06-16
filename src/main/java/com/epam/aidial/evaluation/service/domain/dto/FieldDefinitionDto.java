package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class FieldDefinitionDto {

    @NotBlank
    @Size(max = 255)
    @Pattern(
            regexp = ValidationConstants.IDENTIFIER_NAME_NO_COLON_PATTERN,
            message = ValidationConstants.IDENTIFIER_NAME_NO_COLON_MESSAGE)
    private String name;

    @Size(max = 255)
    private String displayName;

    @NotNull
    private SchemaFieldType type;

    private boolean required;

    @Size(max = 2000)
    private String description;
}
