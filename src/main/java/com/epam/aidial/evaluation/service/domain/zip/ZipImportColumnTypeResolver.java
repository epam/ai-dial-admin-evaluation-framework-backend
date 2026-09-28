package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves each CSV data column's type for ZIP-import cell rewriting purposes (design D5), one call per
 * import. The same result feeds both {@link ZipCsvFileRefRewriter} (deciding which cells to rewrite) and the
 * {@code CsvImportSchemaHints} the ZIP import pipeline builds for {@code CsvImportService}, so the cells
 * actually rewritten and the schema ultimately persisted never disagree about which columns are FILE.
 *
 * <p>Per column and import mode:
 *
 * <ul>
 *   <li>{@code OVERRIDE}, or any mode into a dataset with an empty schema: the manifest's type for that
 *       column, else undetermined. The dataset's current type is ignored, since the schema is about to be
 *       replaced (or there is none yet).
 *   <li>{@code MERGE} into a non-empty schema: the dataset's type, then the manifest's type, else
 *       undetermined.
 *   <li>{@code APPEND} into a non-empty schema: the dataset's type only. A column the dataset schema does
 *       not declare is excluded — never rewritten, and no file is uploaded for it, because {@code
 *       CsvImportService} drops such columns during APPEND.
 * </ul>
 */
@Component
@LogExecution
public class ZipImportColumnTypeResolver {

    /**
     * @param mode the import mode
     * @param datasetSchema the target dataset's current {@code testCaseSchema}; empty when the dataset has
     *     no schema yet
     * @param manifestSchema the ZIP manifest's {@code testCaseSchema}; empty when the archive carries no
     *     manifest
     * @param csvDataColumnNames the CSV's data column names (excluding {@code testCaseName}/{@code
     *     turnIndex})
     * @return one {@link ColumnTypeResolution} per name in {@code csvDataColumnNames}
     */
    public Map<String, ColumnTypeResolution> resolve(
            CsvImportMode mode,
            List<FieldDefinitionDto> datasetSchema,
            List<FieldDefinitionDto> manifestSchema,
            Collection<String> csvDataColumnNames) {
        Map<String, SchemaFieldType> datasetTypes = indexByName(datasetSchema);
        Map<String, SchemaFieldType> manifestTypes = indexByName(manifestSchema);
        boolean datasetSchemaEmpty = datasetTypes.isEmpty();

        Map<String, ColumnTypeResolution> result = new LinkedHashMap<>();
        for (String name : csvDataColumnNames) {
            result.put(name, resolveColumn(mode, datasetSchemaEmpty, datasetTypes, manifestTypes, name));
        }
        return result;
    }

    private static ColumnTypeResolution resolveColumn(
            CsvImportMode mode,
            boolean datasetSchemaEmpty,
            Map<String, SchemaFieldType> datasetTypes,
            Map<String, SchemaFieldType> manifestTypes,
            String name) {
        if (mode == CsvImportMode.OVERRIDE || datasetSchemaEmpty) {
            return new ColumnTypeResolution(manifestTypes.get(name), false);
        }
        if (mode == CsvImportMode.MERGE) {
            SchemaFieldType type = datasetTypes.get(name);
            if (type == null) {
                type = manifestTypes.get(name);
            }
            return new ColumnTypeResolution(type, false);
        }
        // APPEND into a non-empty schema.
        SchemaFieldType type = datasetTypes.get(name);
        return new ColumnTypeResolution(type, type == null);
    }

    private static Map<String, SchemaFieldType> indexByName(List<FieldDefinitionDto> schema) {
        Map<String, SchemaFieldType> byName = new LinkedHashMap<>();
        if (schema == null) {
            return byName;
        }
        for (FieldDefinitionDto field : schema) {
            if (field != null && field.getName() != null && field.getType() != null) {
                byName.put(field.getName(), field.getType());
            }
        }
        return byName;
    }

    /**
     * A column's resolved type for rewriting purposes. {@code type() == null} means undetermined (no
     * dataset or manifest type applies): such a column is treated like {@code FILE} for rewriting, since a
     * CSV alone cannot prove otherwise. {@code excluded()} means the column is not imported at all (APPEND
     * into a non-empty schema, column absent from the dataset schema): never rewritten, no file uploaded.
     */
    public record ColumnTypeResolution(SchemaFieldType type, boolean excluded) {

        /** Whether a whole-cell {@code files/…} value in this column should be rewritten. */
        public boolean eligibleForRewrite() {
            return !excluded && (type == null || type == SchemaFieldType.FILE);
        }
    }
}
