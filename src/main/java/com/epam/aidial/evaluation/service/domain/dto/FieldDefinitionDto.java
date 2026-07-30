package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * Field scope. {@code true} = per-turn: the value may vary between turns of a multi-turn case and
     * lives in each {@code multiTurnData[i]} map. {@code false}/absent = shared (test-case-level): the
     * value is constant across turns and lives in the {@code data} map. Scope is uniform across the
     * dataset; a missing value is treated as shared, so schemas authored before this field are unchanged.
     * For single-turn cases every field lives in {@code data} regardless of scope. Kept as a nullable
     * {@link Boolean} (not a primitive) so pre-existing persisted schemas that omit the field deserialize
     * cleanly rather than failing on a null-into-primitive coercion.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean perTurn;
}
