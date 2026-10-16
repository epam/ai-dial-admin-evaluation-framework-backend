package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Single decision point for whether a full-value {@code ${{...}}} placeholder is a FILE reference whose
 * resolved {@code String} value must be converted to a DIAL ref ({@code files/<bucket>/...}).
 *
 * <p>Rule: an explicit type hint decides ({@code file}, case-insensitive, is FILE; any other hint is not);
 * with no hint the placeholder is FILE iff its binding's {@code dataField} names a schema field of type
 * {@link SchemaFieldType#FILE}. A {@code null}/empty schema, a binding without {@code dataField} (including
 * {@code constantValue}-only bindings) or an unknown field is "not FILE" — only an explicit hint converts.
 *
 * <p>Shared by {@link TemplateContentResolver}, {@link JsonataSourcePreprocessor} and
 * {@link McpRequestResolver} so the three paths cannot drift, and by the backend's {@code BindingValidator}
 * for the column-type lookup.
 */
@Component
@LogExecution
public class FilePlaceholderDetector {

    /**
     * Returns whether a full-value placeholder with the given type hint and binding is a FILE reference.
     *
     * @param typeHint raw type hint from the placeholder ({@code null} when absent)
     * @param binding  the placeholder variable's binding, nullable
     * @param schema   test case schema, nullable
     */
    public boolean isFileReference(String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema) {
        if (typeHint != null && !typeHint.isBlank()) {
            return SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint.trim());
        }
        return boundFieldType(binding, schema)
                .filter(SchemaFieldType.FILE::equals)
                .isPresent();
    }

    /**
     * Returns the schema type of the column named by {@code binding.dataField}, or empty when the binding,
     * its {@code dataField}, the schema or the field is absent.
     */
    public Optional<SchemaFieldType> boundFieldType(InputBindingDto binding, List<FieldDefinitionDto> schema) {
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
}
