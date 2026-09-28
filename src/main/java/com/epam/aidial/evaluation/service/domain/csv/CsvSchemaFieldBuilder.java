package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single owner of {@code FieldDefinitionDto} construction from CSV column bindings. A CSV never
 * <b>declares</b> a field's scope explicitly, so a declared field (one already present in the dataset's
 * current schema) carries its scope forward from that schema, while an undeclared field's scope is
 * <b>inferred</b> from the CSV's turn structure. Every field this builder emits resolves {@code perTurn} in
 * four tiers: (0) <b>manifest-declared</b> (ZIP import only) — the field is one of the {@link
 * ResolvedSchemaHints#drivingFields()} passed in: its definition (type, {@code perTurn}, {@code
 * required}, {@code displayName}, {@code description}) is used verbatim, superseding every tier below,
 * including the dataset's own declared scope; (1) <b>declared</b> — the field name exists in the dataset's
 * current schema: its {@code perTurn} is preserved verbatim, including an absent value (declared shared) —
 * the declared test is the field's presence in the current schema, not the presence of a non-null {@code
 * perTurn}; (2) <b>inferred</b> — the field is undeclared and its name is in {@code multiTurnColumns} (the
 * CSV's observed/over-approximated multi-turn column membership): {@code perTurn} becomes {@code TRUE}; (3)
 * <b>default shared</b> — otherwise, {@code perTurn} is absent. Never mutates an input field definition and
 * never returns an input instance: every emitted field is a new object.
 */
@Component
@LogExecution
public class CsvSchemaFieldBuilder {

    private static final ResolvedSchemaHints NO_HINTS =
            ResolvedSchemaHints.resolve(CsvImportMode.OVERRIDE, List.of(), CsvImportSchemaHints.EMPTY);

    /**
     * Builds a field list from CSV column bindings (data-mapped columns only), with no ZIP schema hints.
     * Equivalent to {@link #buildFromBindings(List, Map, List, Set, ResolvedSchemaHints)} with no driving
     * fields and no file columns.
     */
    public List<FieldDefinitionDto> buildFromBindings(
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            List<FieldDefinitionDto> currentSchema,
            Set<String> multiTurnColumns) {
        return buildFromBindings(bindings, types, currentSchema, multiTurnColumns, NO_HINTS);
    }

    /**
     * Builds a field list from CSV column bindings (data-mapped columns only). {@code types} supplies each
     * field's type by name; when {@code null} (validation-time, before inference has run) every field gets
     * {@code type(null)} unless the manifest tier (0) supplies it. When {@code types} is supplied
     * (persist/fixup/preview-time, after inference), a binding with no entry defaults to {@link
     * SchemaFieldType#STRING}, or to {@link SchemaFieldType#FILE} when {@code hints.fileColumns()} names it
     * (non-empty only when no manifest is present, see {@link ResolvedSchemaHints}). {@code
     * multiTurnColumns} supplies the undeclared-column scope tier (see class javadoc). A driving manifest
     * field with no matching CSV binding is still appended (design D4): the manifest is the source of truth
     * for fields a blank-everywhere CSV column can no longer express.
     */
    public List<FieldDefinitionDto> buildFromBindings(
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            List<FieldDefinitionDto> currentSchema,
            Set<String> multiTurnColumns,
            ResolvedSchemaHints hints) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        Map<String, FieldDefinitionDto> declaredByName = hints.drivingFields();
        Set<String> fileColumns = hints.fileColumns();
        List<FieldDefinitionDto> schema = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())) {
                continue;
            }
            String name = binding.fieldName();
            covered.add(name);
            FieldDefinitionDto declared = declaredByName.get(name);
            schema.add(
                    declared != null
                            ? copyOf(declared)
                            : newField(name, resolveType(name, types, fileColumns), scopeByName, multiTurnColumns));
        }
        for (FieldDefinitionDto declared : declaredByName.values()) {
            if (covered.add(declared.getName())) {
                schema.add(copyOf(declared));
            }
        }
        return schema;
    }

    /**
     * Builds only the CSV columns absent from {@code currentSchema} (the MERGE delta), with no ZIP schema
     * hints. Equivalent to {@link #buildMergeDelta(List, List, Map, Set, ResolvedSchemaHints)} with no
     * driving fields and no file columns.
     */
    public List<FieldDefinitionDto> buildMergeDelta(
            List<FieldDefinitionDto> currentSchema,
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            Set<String> multiTurnColumns) {
        return buildMergeDelta(currentSchema, bindings, types, multiTurnColumns, NO_HINTS);
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
     * field is undeclared by definition, so tier 1 never applies here. When {@code hints} declares a delta
     * field (manifest tier 0), its definition is used verbatim instead of inference — this is the only
     * source of a new field's definition the manifest is allowed to supply in MERGE mode (design D4):
     * existing dataset fields are skipped above and never reach this tier.
     */
    public List<FieldDefinitionDto> buildMergeDelta(
            List<FieldDefinitionDto> currentSchema,
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> types,
            Set<String> multiTurnColumns,
            ResolvedSchemaHints hints) {
        Map<String, Boolean> scopeByName = scopeByName(currentSchema);
        Map<String, FieldDefinitionDto> declaredByName = hints.drivingFields();
        Set<String> fileColumns = hints.fileColumns();
        List<FieldDefinitionDto> delta = new ArrayList<>();
        for (ColumnBinding binding : bindings) {
            if (!ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())
                    || scopeByName.containsKey(binding.fieldName())) {
                continue;
            }
            String name = binding.fieldName();
            FieldDefinitionDto declared = declaredByName.get(name);
            delta.add(
                    declared != null
                            ? copyOf(declared)
                            : newField(name, resolveType(name, types, fileColumns), scopeByName, multiTurnColumns));
        }
        return delta;
    }

    private static SchemaFieldType resolveType(
            String fieldName, Map<String, SchemaFieldType> types, Set<String> fileColumns) {
        if (types == null) {
            return null;
        }
        if (fileColumns.contains(fieldName)) {
            return SchemaFieldType.FILE;
        }
        return types.getOrDefault(fieldName, SchemaFieldType.STRING);
    }

    private static FieldDefinitionDto copyOf(FieldDefinitionDto declared) {
        return FieldDefinitionDto.builder()
                .name(declared.getName())
                .displayName(declared.getDisplayName())
                .type(declared.getType())
                .required(declared.isRequired())
                .description(declared.getDescription())
                .perTurn(declared.getPerTurn())
                .build();
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
