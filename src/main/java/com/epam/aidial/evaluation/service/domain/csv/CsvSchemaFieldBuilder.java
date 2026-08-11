package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single owner of {@code FieldDefinitionDto} construction from CSV column bindings. A CSV never
 * <b>declares</b> a field's scope explicitly, so a declared field (one already present in the dataset's
 * current schema) carries its scope forward from that schema, while an undeclared field's scope is
 * <b>inferred</b> from the CSV's turn structure. Every field this builder emits resolves {@code perTurn} in
 * three tiers: (1) <b>declared</b> — the field name exists in the dataset's current schema: its
 * {@code perTurn} is preserved verbatim, including an absent value (declared shared) — the declared test is
 * the field's presence in the current schema, not the presence of a non-null {@code perTurn}; (2)
 * <b>inferred</b> — the field is undeclared and its name is in {@code multiTurnColumns} (the CSV's
 * observed/over-approximated multi-turn column membership): {@code perTurn} becomes {@code TRUE}; (3)
 * <b>default shared</b> — otherwise, {@code perTurn} is absent. Never mutates an input field definition and
 * never returns an input instance: every emitted field is a new object.
 */
@Component
@LogExecution
public class CsvSchemaFieldBuilder {

    /**
     * Builds a field list from CSV column bindings (data-mapped columns only). {@code types} supplies each
     * field's type by name; when {@code null} (validation-time, before inference has run) every field gets
     * {@code type(null)}. When {@code types} is supplied (persist/fixup/preview-time, after inference), a
     * binding with no entry defaults to {@link SchemaFieldType#STRING}. {@code multiTurnColumns} supplies
     * the undeclared-column scope tier (see class javadoc).
     */
    public List<FieldDefinitionDto> buildFromBindings(
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            List<FieldDefinitionDto> currentSchema,
            Set<String> multiTurnColumns) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        List<FieldDefinitionDto> schema = new ArrayList<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())) {
                continue;
            }
            schema.add(newField(
                    binding.fieldName(), resolveType(binding.fieldName(), types), scopeByName, multiTurnColumns));
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
     * {@code multiTurnColumns} supplies the undeclared-column scope tier (see class javadoc); every delta
     * field is undeclared by definition, so tier 1 never applies here.
     */
    public List<FieldDefinitionDto> buildMergeDelta(
            List<FieldDefinitionDto> currentSchema,
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            Set<String> multiTurnColumns) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        List<FieldDefinitionDto> delta = new ArrayList<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())
                    || scopeByName.containsKey(binding.fieldName())) {
                continue;
            }
            delta.add(newField(
                    binding.fieldName(), resolveType(binding.fieldName(), types), scopeByName, multiTurnColumns));
        }
        return delta;
    }

    private static SchemaFieldType resolveType(String fieldName, Map<String, SchemaFieldType> types) {
        return types != null ? types.getOrDefault(fieldName, SchemaFieldType.STRING) : null;
    }

    private static FieldDefinitionDto newField(
            String name, SchemaFieldType type, Map<String, Boolean> scopeByName, Set<String> multiTurnColumns) {
        Boolean perTurn;
        if (scopeByName.containsKey(name)) {
            perTurn = scopeByName.get(name);
        } else if (multiTurnColumns != null && multiTurnColumns.contains(name)) {
            perTurn = Boolean.TRUE;
        } else {
            perTurn = null;
        }
        return FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(false)
                .perTurn(perTurn)
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
