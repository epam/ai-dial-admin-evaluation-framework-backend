package com.epam.aidial.evaluation.service.domain.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response-only DTO returned by the template-variables convenience API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateVariableDto {

    private String name;
    private Set<TemplateVariableSource> sources;
    private boolean hasDefault;
    private String defaultValue;
    private InputBindingDto binding;

    @Schema(
            description = "Type explicitly declared in the placeholder syntax via |type (e.g., ${{doc|file}}). "
                    + "Null when no type hint is present in the placeholder.",
            nullable = true,
            example = "FILE")
    private SchemaFieldType declaredType;

    @JsonAlias("inferredType")
    @Schema(
            description = "Fully resolved type for this variable, determined by priority chain: "
                    + "(1) declaredType from placeholder syntax, "
                    + "(2) endpointRef schema type, "
                    + "(3) testCaseSchema field type via binding's dataField, "
                    + "(4) STRING fallback.",
            example = "STRING")
    private SchemaFieldType effectiveType;

    @Schema(
            description = "Resolved typed value for this variable. "
                    + "At suite level: populated for constant-value bindings and template defaults; "
                    + "null for data-field bindings (no test case data). "
                    + "At test-case level: fully resolved using bindings and test case data.",
            nullable = true,
            example = "gpt-4")
    private Object resolvedValue;
}
