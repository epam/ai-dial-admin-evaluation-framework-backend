package com.epam.aidial.evaluation.runner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runner-module twin of the EF backend's {@code service.domain.dto.FieldDefinitionDto} (dataset test-case
 * schema field definition), duplicated here — like {@link SuiteType} / {@code ValidationConstants} /
 * {@code ValidationException} — so the module can read {@link #getPerTurn()} / {@link #getName()} off
 * {@code EvaluationContext.snapshotTestCaseSchema} without a module-to-main-app dependency. The EF backend
 * remains the sole writer/validator of the schema; this module only ever reads a snapshot copy.
 */
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
     * value is constant across turns and lives in the {@code data} map.
     */
    @Schema(
            description = "Whether the field varies per turn (true) or is shared/test-case-level (false, default)",
            example = "false")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean perTurn;
}
