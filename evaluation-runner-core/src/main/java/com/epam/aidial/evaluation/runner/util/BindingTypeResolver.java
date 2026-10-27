package com.epam.aidial.evaluation.runner.util;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the schema type of the test-case column a placeholder's input binding points at.
 *
 * <p>The type decisions built on top of it ({@code DialFileRefResolver#isFileReference},
 * {@code SkillRefResolver#isSkillReference}) live next to the reference format they guard; this component only
 * answers "which schema field type does this binding name?". A {@code null}/empty schema, a binding without
 * {@code dataField} (including {@code constantValue}-only bindings) or an unknown field yields empty.
 */
@Component
@LogExecution
public class BindingTypeResolver {

    /**
     * Returns the schema type of the column named by {@code binding.dataField}, or empty when the binding,
     * its {@code dataField}, the schema or the field is absent.
     */
    public Optional<SchemaFieldType> resolveType(InputBindingDto binding, List<FieldDefinitionDto> schema) {
        if (binding == null || schema == null || schema.isEmpty()) {
            return Optional.empty();
        }
        final String dataField = binding.getDataField();
        if (dataField == null || dataField.isBlank()) {
            return Optional.empty();
        }
        return schema.stream()
                .filter(field -> field != null && dataField.equals(field.getName()))
                .map(FieldDefinitionDto::getType)
                .findFirst();
    }

    /**
     * Returns whether a full-value placeholder is of {@code type}: an explicit type hint decides
     * (case-insensitive, trimmed; any other hint is not that type), and with no (or a blank) hint the type of
     * the bound column decides.
     *
     * @param type     the schema type being asked about
     * @param typeHint raw type hint from the placeholder ({@code null} when absent)
     * @param binding  the placeholder variable's binding, nullable
     * @param schema   test case schema, nullable
     */
    public boolean isOfType(
            SchemaFieldType type, String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema) {
        if (typeHint != null && !typeHint.isBlank()) {
            return type.name().equalsIgnoreCase(typeHint.trim());
        }
        return resolveType(binding, schema).filter(type::equals).isPresent();
    }
}
