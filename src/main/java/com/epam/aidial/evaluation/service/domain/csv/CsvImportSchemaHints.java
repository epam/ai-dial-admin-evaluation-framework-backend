package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import java.util.List;
import java.util.Set;

/**
 * Optional schema hints that a ZIP import (or any other future caller) can supply to {@code
 * CsvImportService} on top of the CSV headers alone, so a ZIP archive's {@code manifest.json} — or, absent
 * a manifest, its file-reference columns — can influence the schema import derives. Plain CSV import always
 * passes {@link #EMPTY}, so its behaviour is unchanged.
 *
 * <ul>
 *   <li>{@code declaredSchema} — the manifest's {@code testCaseSchema}, verbatim, when the ZIP carries a
 *       valid manifest; empty when it does not. A field listed here takes priority over every inference
 *       tier (design D4, tier 0): its {@code type}, {@code perTurn}, {@code required}, {@code displayName}
 *       and {@code description} are used as written, wherever the import mode lets the manifest decide that
 *       field's definition.
 *   <li>{@code fileColumns} — the set of CSV data column names in which at least one cell referenced an
 *       archive {@code files/…} path. Consulted only when {@code declaredSchema} is empty (no manifest): a
 *       column named here is typed {@code FILE} wherever import derives a type for it, instead of the usual
 *       generic inference.
 * </ul>
 *
 * <p>Deliberately declared in {@code service.domain.csv}, not {@code service.domain.zip}: the CSV package
 * must never depend on the ZIP package, so this hint type carries only CSV-shaped data ({@link
 * FieldDefinitionDto}, {@code Set<String>}) and never references a ZIP archive, manifest or entry type.
 */
public record CsvImportSchemaHints(List<FieldDefinitionDto> declaredSchema, Set<String> fileColumns) {

    public static final CsvImportSchemaHints EMPTY = new CsvImportSchemaHints(List.of(), Set.of());

    public CsvImportSchemaHints {
        declaredSchema = declaredSchema != null ? List.copyOf(declaredSchema) : List.of();
        fileColumns = fileColumns != null ? Set.copyOf(fileColumns) : Set.of();
    }
}
