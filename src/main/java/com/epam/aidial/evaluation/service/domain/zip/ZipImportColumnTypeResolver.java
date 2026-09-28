package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.csv.CsvImportSchemaHints;
import com.epam.aidial.evaluation.service.domain.csv.ResolvedSchemaHints;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves each CSV data column's type for ZIP-import cell rewriting purposes (design D5), one call per
 * import. The rule table itself lives in {@link ResolvedSchemaHints} (the same one {@code CsvImportService}
 * applies when it parses and persists), so the cells rewritten here and the schema ultimately persisted
 * never disagree about which columns are FILE or dropped:
 *
 * <ul>
 *   <li>type: {@link ResolvedSchemaHints#effectiveType} — the manifest's for a driving field, else the
 *       dataset's unless OVERRIDE/empty schema replaces it, else undetermined;
 *   <li>excluded: {@link ResolvedSchemaHints#excludes} — APPEND into a non-empty schema that does not type
 *       the column: never rewritten, and no file is uploaded for it.
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
        ResolvedSchemaHints hints =
                ResolvedSchemaHints.resolve(mode, datasetSchema, new CsvImportSchemaHints(manifestSchema, Set.of()));
        Map<String, ColumnTypeResolution> result = new LinkedHashMap<>();
        for (String name : csvDataColumnNames) {
            result.put(name, new ColumnTypeResolution(hints.effectiveType(name), hints.excludes(name)));
        }
        return result;
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
