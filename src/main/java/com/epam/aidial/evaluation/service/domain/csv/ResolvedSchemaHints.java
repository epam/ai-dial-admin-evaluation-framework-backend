package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CsvImportSchemaHints} resolved against one import's mode and the target dataset's current schema.
 * This is the <b>single owner</b> of the manifest-precedence table (design D4/D5). Every consumer reads it:
 * CSV parsing, schema building, persistence, preview, and the ZIP rewrite-type resolver. None of them
 * re-derives the table from the mode.
 *
 * <table>
 *   <caption>Manifest precedence</caption>
 *   <tr><th>Mode / schema state</th><th>Driving (manifest-decided) fields</th><th>Other columns</th></tr>
 *   <tr><td>OVERRIDE, or any mode into an empty schema</td><td>every manifest field</td>
 *       <td>type undetermined (dataset types are about to be replaced)</td></tr>
 *   <tr><td>MERGE into a non-empty schema</td><td>manifest fields the dataset does not declare</td>
 *       <td>the dataset's type</td></tr>
 *   <tr><td>APPEND into a non-empty schema</td><td>none (manifest ignored)</td>
 *       <td>the dataset's type; an undeclared column is dropped</td></tr>
 * </table>
 *
 * <p>{@code fileColumns} is the no-manifest fallback: it is non-empty only when the hints carry no
 * manifest fields. Plain CSV import resolves {@link CsvImportSchemaHints#EMPTY}, which yields no driving
 * fields and no file columns, so plain-CSV behaviour is unchanged.
 *
 * @param drivingFields manifest fields whose definition this import takes verbatim, by name, in manifest order
 * @param fileColumns columns typed {@code FILE} when no manifest is present
 * @param replacesDatasetTypes OVERRIDE or empty schema: the dataset's current types do not apply
 * @param dropsUndeclared APPEND into a non-empty schema: columns the dataset does not type are not imported
 * @param datasetTypes the dataset's current field types, by name (fields with a null type omitted)
 */
public record ResolvedSchemaHints(
        Map<String, FieldDefinitionDto> drivingFields,
        Set<String> fileColumns,
        boolean replacesDatasetTypes,
        boolean dropsUndeclared,
        Map<String, SchemaFieldType> datasetTypes) {

    public ResolvedSchemaHints {
        drivingFields = Collections.unmodifiableMap(new LinkedHashMap<>(drivingFields));
        fileColumns = Set.copyOf(fileColumns);
        datasetTypes = Collections.unmodifiableMap(new LinkedHashMap<>(datasetTypes));
    }

    public static ResolvedSchemaHints resolve(
            CsvImportMode mode, List<FieldDefinitionDto> datasetSchema, CsvImportSchemaHints hints) {
        boolean schemaEmpty = datasetSchema == null || datasetSchema.isEmpty();
        boolean replacesDatasetTypes = mode == CsvImportMode.OVERRIDE || schemaEmpty;
        boolean dropsUndeclared = mode == CsvImportMode.APPEND && !schemaEmpty;
        Set<String> datasetNames = namesOf(datasetSchema);

        Map<String, FieldDefinitionDto> driving = new LinkedHashMap<>();
        if (!dropsUndeclared) {
            for (FieldDefinitionDto field : hints.declaredSchema()) {
                if (field == null || field.getName() == null) {
                    continue;
                }
                if (replacesDatasetTypes || !datasetNames.contains(field.getName())) {
                    driving.putIfAbsent(field.getName(), field);
                }
            }
        }
        Set<String> fileColumns = hints.declaredSchema().isEmpty() ? hints.fileColumns() : Set.of();
        return new ResolvedSchemaHints(
                driving, fileColumns, replacesDatasetTypes, dropsUndeclared, typesOf(datasetSchema));
    }

    /** Whether the manifest decides this column's definition (and its raw-text parsing) in this import. */
    public boolean drives(String name) {
        return drivingFields.containsKey(name);
    }

    /**
     * The column's type as this import sees it before inference: the manifest's when it drives the column,
     * else the dataset's unless the dataset's types are being replaced; {@code null} = undetermined.
     */
    public SchemaFieldType effectiveType(String name) {
        FieldDefinitionDto driving = drivingFields.get(name);
        if (driving != null) {
            return driving.getType();
        }
        return replacesDatasetTypes ? null : datasetTypes.get(name);
    }

    /** Whether the column is dropped by this import (APPEND into a non-empty schema that does not type it). */
    public boolean excludes(String name) {
        return dropsUndeclared && !datasetTypes.containsKey(name);
    }

    private static Set<String> namesOf(List<FieldDefinitionDto> schema) {
        Set<String> names = new LinkedHashSet<>();
        if (schema != null) {
            for (FieldDefinitionDto field : schema) {
                if (field != null && field.getName() != null) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    private static Map<String, SchemaFieldType> typesOf(List<FieldDefinitionDto> schema) {
        Map<String, SchemaFieldType> types = new LinkedHashMap<>();
        if (schema != null) {
            for (FieldDefinitionDto field : schema) {
                if (field != null && field.getName() != null && field.getType() != null) {
                    types.put(field.getName(), field.getType());
                }
            }
        }
        return types;
    }
}
