package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Single owner of {@code FieldDefinitionDto} construction from CSV column bindings. A CSV expresses only
 * values, never a field's scope, so every field this builder emits carries {@code perTurn} forward from
 * the dataset's current schema by field name — a column with no same-named current field is new and gets
 * {@code perTurn} absent (shared). Never mutates an input field definition and never returns an input
 * instance: every emitted field is a new object.
 */
@Component
@LogExecution
public class CsvSchemaFieldBuilder {

    /**
     * Builds a field list from CSV column bindings (data-mapped columns only). {@code types} supplies each
     * field's type by name; when {@code null} (validation-time, before inference has run) every field gets
     * {@code type(null)}. When {@code types} is supplied (persist/fixup/preview-time, after inference), a
     * binding with no entry defaults to {@link SchemaFieldType#STRING}.
     */
    public List<FieldDefinitionDto> buildFromBindings(
            List<ColumnBinding> bindings, Map<String, SchemaFieldType> types, List<FieldDefinitionDto> currentSchema) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        List<FieldDefinitionDto> schema = new ArrayList<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())) {
                continue;
            }
            schema.add(newField(binding.fieldName(), resolveType(binding.fieldName(), types), scopeByName));
        }
        return schema;
    }

    /**
     * Builds only the CSV columns absent from {@code currentSchema} (the MERGE delta) — new fields to
     * append to the dataset's existing schema. {@code types} supplies each new field's type the same way as
     * {@link #buildFromBindings}: a binding with no entry in {@code types} is emitted as
     * {@link SchemaFieldType#STRING} rather than omitted — so a new column that is blank on every CSV row
     * (and therefore never reaches {@code types}, since type inference skips blank cells) still becomes a
     * schema field. This intentionally aligns the persisted/fixup MERGE delta with preview's
     * {@code autoDetectedSchema}, which already iterated bindings the same way; the two no longer diverge.
     */
    public List<FieldDefinitionDto> buildMergeDelta(
            List<FieldDefinitionDto> currentSchema, List<ColumnBinding> bindings, Map<String, SchemaFieldType> types) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        List<FieldDefinitionDto> delta = new ArrayList<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())
                    || scopeByName.containsKey(binding.fieldName())) {
                continue;
            }
            delta.add(newField(binding.fieldName(), resolveType(binding.fieldName(), types), scopeByName));
        }
        return delta;
    }

    private static SchemaFieldType resolveType(String fieldName, Map<String, SchemaFieldType> types) {
        return types != null ? types.getOrDefault(fieldName, SchemaFieldType.STRING) : null;
    }

    private static FieldDefinitionDto newField(String name, SchemaFieldType type, Map<String, Boolean> scopeByName) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(false)
                .perTurn(scopeByName.get(name))
                .build();
    }

    private static Map<String, Boolean> scopeByName(List<FieldDefinitionDto> currentSchema) {
        Map<String, Boolean> scope = new LinkedHashMap<>();
        if (currentSchema != null) {
            for (FieldDefinitionDto field : currentSchema) {
                if (field != null && field.getName() != null) {
                    scope.put(field.getName(), field.getPerTurn());
                }
            }
        }
        return scope;
    }
}
