package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.csv.CsvCellParser;
import com.epam.aidial.evaluation.service.domain.csv.SchemaTypeCoercer;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvColumnInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class CsvImportService {

    private static final String TEST_CASE_NAME_HEADER = "testCaseName";
    private static final String TURN_INDEX_HEADER = "turnIndex";
    private static final int SAMPLE_ROWS_LIMIT = 10;

    private final DatasetRepository datasetRepository;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestCaseRepository testCaseRepository;
    private final TestCaseValidationService testCaseValidationService;
    private final RevalidationService revalidationService;
    private final CsvImportProperties csvImportProperties;
    private final CsvCellParser csvCellParser;
    private final SchemaTypeCoercer schemaTypeCoercer;
    private final ObjectMapper objectMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TestCaseFieldScopeResolver scopeResolver;

    /**
     * Dry-run: parse and validate without persisting. Returns preview with detected columns and sample rows.
     * Streams through CSV and keeps only first SAMPLE_ROWS_LIMIT rows in memory for sample.
     * When testCaseSchema is empty/null, auto-detects column types from data.
     */
    public CsvImportPreviewDto preview(
            UUID datasetId,
            InputStream inputStream,
            long contentLength,
            char delimiter,
            CsvImportMode mode,
            CsvConflictStrategy conflictStrategy) {
        validateFileSize(contentLength);
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
        List<FieldDefinitionDto> testCaseSchema = datasetSchemaProvider.getSchema(datasetId);

        try (CSVParser parser = createParser(inputStream, delimiter)) {
            List<String> headers = parseHeader(parser);
            if (headers.isEmpty()) {
                throw new ValidationException("CSV has no header row");
            }
            List<ColumnBinding> bindings = resolveColumnBindings(headers);
            Map<String, SchemaFieldType> fieldTypes = getFieldTypes(testCaseSchema);

            boolean schemaEmpty = testCaseSchema == null || testCaseSchema.isEmpty();
            List<FieldDefinitionDto> validationSchema =
                    buildValidationSchema(mode, schemaEmpty, bindings, testCaseSchema);

            List<CsvImportWarningDto> warnings = new ArrayList<>();
            List<TestCaseResponseDto> sampleRows = new ArrayList<>();
            int totalRows = 0;
            int rowNum = 1;
            int padWidth = String.valueOf(csvImportProperties.getMaxRows()).length();

            // Incremental type inference for preview schema detection
            Map<String, SchemaFieldType> inferredTypes = new LinkedHashMap<>();
            // Within-CSV duplicate tracking
            LinkedHashSet<String> seenNames = new LinkedHashSet<>();
            // Names from CSV for collision detection (APPEND/MERGE)
            List<String> allCsvNames = new ArrayList<>();

            for (CSVRecord record : parser) {
                if (totalRows >= csvImportProperties.getMaxRows()) {
                    throw new ValidationException("Row count exceeds limit " + csvImportProperties.getMaxRows());
                }
                rowNum++;
                ParsedRow row =
                        parseRow(record, bindings, rowNum, totalRows + 1, padWidth, fieldTypes, mode, schemaEmpty);
                totalRows++;
                allCsvNames.add(row.testCaseName());

                // Incremental schema inference
                if (shouldAutoDetectSchema(mode, schemaEmpty)) {
                    updateInferredTypes(record, bindings, inferredTypes);
                } else if (mode == CsvImportMode.MERGE && !schemaEmpty) {
                    updateInferredTypesForNewFields(record, bindings, fieldTypes, inferredTypes);
                }

                // Within-CSV duplicate detection
                String lowerName = row.testCaseName().toLowerCase();
                boolean isDuplicateWithinCsv = !seenNames.add(lowerName);
                if (isDuplicateWithinCsv) {
                    warnings.add(buildWithinCsvDupWarning(row.rowNumber(), row.testCaseName(), conflictStrategy));
                }

                ValidationResult vr = testCaseValidationService.validateTestCase(
                        row.data(), validationSchema, null, List.of(), false, datasetId);
                ValidationResult combined = combineWithJsonParseErrors(vr, row);
                if (!combined.isValid() && combined.getWarnings() != null) {
                    for (ValidationWarningDto w : combined.getWarnings()) {
                        warnings.add(CsvImportWarningDto.builder()
                                .rowNumber(row.rowNumber())
                                .columnName(w.getFieldName() != null ? w.getFieldName() : "")
                                .message(w.getMessage())
                                .build());
                    }
                }
                if (sampleRows.size() < SAMPLE_ROWS_LIMIT) {
                    sampleRows.add(toResponseDto(row, combined));
                }
            }

            if (totalRows == 0) {
                throw new ValidationException("Empty CSV (header only, no data rows)");
            }

            // Cross-import collision detection for APPEND/MERGE (no DB query needed for OVERRIDE)
            if (mode != CsvImportMode.OVERRIDE && !allCsvNames.isEmpty()) {
                addCollisionWarnings(datasetId, allCsvNames, seenNames, warnings, conflictStrategy);
            }

            List<FieldDefinitionDto> autoDetectedSchema =
                    buildAutoDetectedSchema(mode, schemaEmpty, bindings, testCaseSchema, inferredTypes);

            List<CsvColumnInfoDto> detectedColumns =
                    buildDetectedColumns(headers, bindings, fieldTypes, autoDetectedSchema);

            return CsvImportPreviewDto.builder()
                    .detectedColumns(detectedColumns)
                    .totalRows(totalRows)
                    .sampleRows(sampleRows)
                    .warnings(warnings.isEmpty() ? List.of() : warnings)
                    .autoDetectedSchema(autoDetectedSchema)
                    .build();
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            log.warn("CSV parse error: {}", msg, e);
            throw new ValidationException("Malformed CSV: " + msg);
        } catch (IOException e) {
            log.warn("CSV parse error: {}", e.getMessage(), e);
            throw new ValidationException("Malformed CSV: " + e.getMessage());
        }
    }

    /**
     * Parse and persist CSV rows with configurable import mode and conflict strategy.
     * Processes CSV in batches (configurable batch size) without loading entire file into memory.
     * When the dataset schema is persisted (OVERRIDE / APPEND-with-empty-schema / MERGE-with-new-fields),
     * triggers a dataset-rooted revalidation task so downstream suites pick up the new schema.
     */
    @Transactional("metaTransactionManager")
    public CsvImportResultDto importCsv(
            UUID datasetId,
            InputStream inputStream,
            long contentLength,
            char delimiter,
            Long expectedVersion,
            CsvImportMode mode,
            CsvConflictStrategy conflictStrategy) {
        validateFileSize(contentLength);
        Dataset dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new EntityNotFoundException("Dataset not found: " + datasetId));
        if (expectedVersion != null && !expectedVersion.equals(dataset.getVersion())) {
            throw new VersionConflictException(
                    "Dataset version conflict: expected " + expectedVersion + " but current is " + dataset.getVersion(),
                    datasetId,
                    expectedVersion);
        }

        List<FieldDefinitionDto> testCaseSchema = datasetSchemaProvider.getSchema(datasetId);

        try (CSVParser parser = createParser(inputStream, delimiter)) {
            List<String> headers = parseHeader(parser);
            if (headers.isEmpty()) {
                throw new ValidationException("CSV has no header row");
            }
            List<ColumnBinding> bindings = resolveColumnBindings(headers);
            Map<String, SchemaFieldType> fieldTypes = getFieldTypes(testCaseSchema);

            boolean schemaEmpty = testCaseSchema == null || testCaseSchema.isEmpty();
            List<FieldDefinitionDto> validationSchema =
                    buildValidationSchema(mode, schemaEmpty, bindings, testCaseSchema);

            List<CsvImportWarningDto> warnings = new ArrayList<>();
            int totalRows = 0;
            int validCount = 0;
            int invalidCount = 0;
            int skippedCount = 0;
            int overriddenCount = 0;
            int rowNum = 1;
            int maxRows = csvImportProperties.getMaxRows();
            int padWidth = String.valueOf(maxRows).length();

            // Incremental type inference for schema detection
            Map<String, SchemaFieldType> inferredTypes = new LinkedHashMap<>();

            // OVERRIDE mode: delete all existing rows first
            if (mode == CsvImportMode.OVERRIDE) {
                testCaseRepository.deleteAllByDatasetId(datasetId, List.of());
            }

            // Flat multiplication: consecutive rows sharing a testCaseName form one "run" — a single-turn
            // case (one row, blank turnIndex) or a multi-turn case (assembled into multiTurnData). A run is
            // flushed as soon as the name changes, keeping import streaming/bounded-memory.
            List<ParsedRow> currentRun = new ArrayList<>();
            Set<String> completedRunNames = new HashSet<>();
            for (CSVRecord record : parser) {
                if (totalRows >= maxRows) {
                    throw new ValidationException("Row count exceeds limit " + maxRows);
                }
                rowNum++;
                ParsedRow row =
                        parseRow(record, bindings, rowNum, totalRows + 1, padWidth, fieldTypes, mode, schemaEmpty);
                totalRows++;

                // Incremental schema inference per row
                if (shouldAutoDetectSchema(mode, schemaEmpty)) {
                    updateInferredTypes(record, bindings, inferredTypes);
                } else if (mode == CsvImportMode.MERGE && !schemaEmpty) {
                    updateInferredTypesForNewFields(record, bindings, fieldTypes, inferredTypes);
                }

                if (!currentRun.isEmpty() && !currentRun.get(0).testCaseName().equals(row.testCaseName())) {
                    InsertResult result = processRun(
                            currentRun, datasetId, validationSchema, conflictStrategy, warnings, completedRunNames);
                    validCount += result.validCount();
                    invalidCount += result.invalidCount();
                    skippedCount += result.skippedCount();
                    overriddenCount += result.overriddenCount();
                    currentRun = new ArrayList<>();
                }
                currentRun.add(row);
            }

            if (totalRows == 0) {
                throw new ValidationException("Empty CSV (header only, no data rows)");
            }

            // Process the final run
            if (!currentRun.isEmpty()) {
                InsertResult result = processRun(
                        currentRun, datasetId, validationSchema, conflictStrategy, warnings, completedRunNames);
                validCount += result.validCount();
                invalidCount += result.invalidCount();
                skippedCount += result.skippedCount();
                overriddenCount += result.overriddenCount();
            }

            // Schema persistence after streaming completes
            boolean schemaPersisted =
                    persistSchema(datasetId, mode, schemaEmpty, bindings, testCaseSchema, inferredTypes);

            // Post-persist fixup: coerce values for columns with newly determined types
            Set<String> changedColumns = computeChangedColumns(mode, schemaEmpty, fieldTypes, inferredTypes);
            if (!changedColumns.isEmpty()) {
                List<FieldDefinitionDto> finalSchema =
                        buildFinalSchema(mode, schemaEmpty, bindings, testCaseSchema, inferredTypes);
                fixupTestCases(datasetId, changedColumns, inferredTypes, finalSchema);
            }

            // Trigger dataset-rooted revalidation when the dataset schema was persisted.
            // This refreshes Phase-2 suite-level validation for every suite referencing this dataset.
            if (schemaPersisted) {
                revalidationService.startDatasetRevalidation(datasetId);
            }

            CsvImportResultDto.CsvImportResultDtoBuilder builder = CsvImportResultDto.builder()
                    .totalRows(totalRows)
                    .validCount(validCount)
                    .invalidCount(invalidCount)
                    .warnings(warnings.isEmpty() ? List.of() : warnings);

            switch (conflictStrategy) {
                case SKIP -> builder.skippedCount(skippedCount);
                case OVERRIDE -> builder.overriddenCount(overriddenCount);
                default -> {}
            }

            return builder.build();
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            String msg = cause != null ? cause.getMessage() : e.getMessage();
            log.warn("CSV parse error: {}", msg, e);
            throw new ValidationException("Malformed CSV: " + msg);
        } catch (IOException e) {
            log.warn("CSV parse error: {}", e.getMessage(), e);
            throw new ValidationException("Malformed CSV: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Batch processing
    // -------------------------------------------------------------------------

    /**
     * Processes one contiguous run of rows sharing a testCaseName: a single-turn case (one row, blank
     * turnIndex) or a multi-turn case (assembled into {@code multiTurnData}). A non-contiguous reappearance
     * of a name is reported as a conflict warning.
     */
    private InsertResult processRun(
            List<ParsedRow> run,
            UUID datasetId,
            List<FieldDefinitionDto> testCaseSchema,
            CsvConflictStrategy conflictStrategy,
            List<CsvImportWarningDto> warnings,
            Set<String> completedMultiTurnNames) {
        // A run is multi-turn only when it carries an explicit turnIndex; otherwise a run of same-named rows
        // is a set of single-turn duplicates handled per-row by the conflict strategy (unchanged behavior).
        boolean multiTurn = run.stream().anyMatch(r -> r.turnIndex() != null);
        if (!multiTurn) {
            int validCount = 0;
            int invalidCount = 0;
            int skippedCount = 0;
            int overriddenCount = 0;
            for (ParsedRow row : run) {
                ValidationResult vr = testCaseValidationService.validateTestCase(
                        row.data(), testCaseSchema, null, List.of(), false, datasetId);
                ValidationResult combined = combineWithJsonParseErrors(vr, row);
                collectWarnings(combined, row.rowNumber(), warnings);
                InsertResult r =
                        persist(toEntity(row, datasetId, combined), combined, conflictStrategy, row.testCaseName());
                validCount += r.validCount();
                invalidCount += r.invalidCount();
                skippedCount += r.skippedCount();
                overriddenCount += r.overriddenCount();
            }
            return new InsertResult(validCount, invalidCount, skippedCount, overriddenCount);
        }

        ParsedRow first = run.get(0);
        if (!completedMultiTurnNames.add(first.testCaseName().toLowerCase())) {
            warnings.add(CsvImportWarningDto.builder()
                    .rowNumber(first.rowNumber())
                    .columnName(TEST_CASE_NAME_HEADER)
                    .message("Test case name '" + first.testCaseName()
                            + "' appears non-contiguously; multi-turn rows of a case must be contiguous")
                    .build());
        }
        List<ParsedRow> ordered = orderTurns(run);
        MultiTurnAssembly assembly = assembleMultiTurn(ordered, testCaseSchema);
        ValidationResult combined = validateRunAsMultiTurn(run, ordered, assembly, datasetId, testCaseSchema, warnings);
        return persist(
                toMultiTurnEntity(first.testCaseName(), datasetId, assembly, combined),
                combined,
                conflictStrategy,
                first.testCaseName());
    }

    /**
     * Splits an ordered multi-turn run into its shared (test-case-level) data map and its ordered per-turn
     * maps, using each column's schema scope. Shared columns must be identical across the run's rows; a
     * mismatch is flagged so validation can invalidate the case.
     */
    private MultiTurnAssembly assembleMultiTurn(List<ParsedRow> ordered, List<FieldDefinitionDto> schema) {
        Map<String, Object> shared = null;
        boolean sharedConflict = false;
        List<Map<String, Object>> perTurnMaps = new ArrayList<>(ordered.size());
        for (ParsedRow row : ordered) {
            TestCaseFieldScopeResolver.Partition partition = scopeResolver.partition(row.data(), schema);
            if (shared == null) {
                shared = partition.shared();
            } else if (!shared.equals(partition.shared())) {
                sharedConflict = true;
            }
            perTurnMaps.add(partition.perTurn());
        }
        return new MultiTurnAssembly(shared != null ? shared : Map.of(), perTurnMaps, sharedConflict);
    }

    /** A CSV multi-turn run split by scope: the case's shared data and its ordered per-turn maps. */
    private record MultiTurnAssembly(
            Map<String, Object> sharedData, List<Map<String, Object>> perTurnMaps, boolean sharedConflict) {}

    /** Persists one assembled entity (single-turn row or multi-turn case) via the conflict strategy. */
    private InsertResult persist(
            TestCase entity, ValidationResult combined, CsvConflictStrategy conflictStrategy, String name) {
        int skippedCount = 0;
        int overriddenCount = 0;
        switch (conflictStrategy) {
            case FAIL -> {
                try {
                    testCaseRepository.save(entity);
                } catch (DataIntegrityViolationException ex) {
                    UniqueConstraintViolationDetector.rethrowIfUniqueViolation(
                            ex, "Duplicate test case name in CSV or existing data: '" + name + "'", name);
                    throw ex;
                }
            }
            case SKIP -> {
                if (testCaseRepository.insertOrSkip(entity) == 0) {
                    skippedCount++;
                }
            }
            case OVERRIDE -> {
                if (testCaseRepository.insertOrOverride(entity)) {
                    overriddenCount++;
                }
            }
            default -> throw new IllegalStateException("Unknown conflictStrategy: " + conflictStrategy);
        }
        int validCount = combined.isValid() ? 1 : 0;
        int invalidCount = combined.isValid() ? 0 : 1;
        return new InsertResult(validCount, invalidCount, skippedCount, overriddenCount);
    }

    private ValidationResult validateRunAsMultiTurn(
            List<ParsedRow> run,
            List<ParsedRow> ordered,
            MultiTurnAssembly assembly,
            UUID datasetId,
            List<FieldDefinitionDto> schema,
            List<CsvImportWarningDto> warnings) {
        ValidationResult vr = testCaseValidationService.validateMultiTurn(
                assembly.sharedData(), assembly.perTurnMaps(), schema, null, List.of(), false, datasetId);

        List<ValidationWarningDto> merged = new ArrayList<>(vr.getWarnings() != null ? vr.getWarnings() : List.of());
        boolean valid = vr.isValid();
        if (assembly.sharedConflict()) {
            valid = false;
            merged.add(ValidationWarningDto.builder()
                    .message("Shared (test-case-level) column values differ across turns of case '"
                            + run.get(0).testCaseName() + "'; they must be identical")
                    .code(ValidationWarningCode.ADDITIONAL)
                    .build());
        }
        if (ordered.stream().anyMatch(ParsedRow::hasJsonParseErrors)) {
            valid = false;
            merged.add(ValidationWarningDto.builder()
                    .message("Cell could not be parsed as JSON for OBJECT/ARRAY field")
                    .code(ValidationWarningCode.UNKNOWN)
                    .build());
        }
        if (hasDuplicateTurnIndex(run)) {
            valid = false;
            merged.add(ValidationWarningDto.builder()
                    .fieldName(TURN_INDEX_HEADER)
                    .message("Duplicate turnIndex within multi-turn case '"
                            + run.get(0).testCaseName() + "'")
                    .code(ValidationWarningCode.ADDITIONAL)
                    .build());
        }
        ValidationResult combined =
                ValidationResult.builder().valid(valid).warnings(merged).build();
        collectWarnings(combined, run.get(0).rowNumber(), warnings);
        return combined;
    }

    private void collectWarnings(ValidationResult vr, int rowNumber, List<CsvImportWarningDto> warnings) {
        if (vr.isValid() || vr.getWarnings() == null) {
            return;
        }
        for (ValidationWarningDto w : vr.getWarnings()) {
            warnings.add(CsvImportWarningDto.builder()
                    .rowNumber(rowNumber)
                    .columnName(w.getFieldName() != null ? w.getFieldName() : "")
                    .message(w.getMessage())
                    .build());
        }
    }

    /** Orders a multi-turn run by its turnIndex ordering hint (nulls last, stable by CSV row order). */
    private List<ParsedRow> orderTurns(List<ParsedRow> run) {
        List<ParsedRow> ordered = new ArrayList<>(run);
        ordered.sort(Comparator.comparingInt((ParsedRow r) -> r.turnIndex() == null ? Integer.MAX_VALUE : r.turnIndex())
                .thenComparingInt(ParsedRow::rowNumber));
        return ordered;
    }

    private boolean hasDuplicateTurnIndex(List<ParsedRow> run) {
        Set<Integer> seen = new HashSet<>();
        for (ParsedRow r : run) {
            if (r.turnIndex() != null && !seen.add(r.turnIndex())) {
                return true;
            }
        }
        return false;
    }

    private TestCase toMultiTurnEntity(
            String testCaseName, UUID datasetId, MultiTurnAssembly assembly, ValidationResult vr) {
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(testCaseName)
                .data(warningsSerializer.serializeMap(assembly.sharedData()))
                .multiTurnData(warningsSerializer.serializeTurns(assembly.perTurnMaps()))
                .valid(vr.isValid())
                .validationWarnings(warningsSerializer.serializeWarnings(vr.getWarnings()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Validation schema: target schema for validating imported rows
    // -------------------------------------------------------------------------

    /**
     * Builds the schema to validate imported rows against, based on import mode and current schema state.
     * This avoids validating against the stale pre-import schema.
     *
     * <ul>
     *   <li>OVERRIDE (any schema state): all data columns from CSV headers (required=false)</li>
     *   <li>MERGE + non-empty schema: existing schema + new CSV columns (required=false)</li>
     *   <li>APPEND/MERGE + empty schema: all data columns from CSV headers (required=false)</li>
     *   <li>APPEND + non-empty schema: existing testCaseSchema unchanged</li>
     * </ul>
     */
    List<FieldDefinitionDto> buildValidationSchema(
            CsvImportMode mode,
            boolean schemaEmpty,
            List<ColumnBinding> bindings,
            List<FieldDefinitionDto> testCaseSchema) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            // OVERRIDE rebuilds field defs from CSV columns (types inferred later) and so loses field scope;
            // scope (perTurn) is a persistent dataset-schema property, so re-apply it by field name. MERGE
            // and APPEND already carry the existing schema's fields (with their perTurn) through unchanged.
            return applyScopeFromDataset(buildSchemaFromBindings(bindings), testCaseSchema);
        }
        if (mode == CsvImportMode.MERGE) {
            return mergeSchemaWithBindings(testCaseSchema, bindings);
        }
        // APPEND + non-empty schema: use existing schema as-is
        return testCaseSchema != null ? testCaseSchema : List.of();
    }

    /**
     * Copies each field's {@code perTurn} scope from the dataset's current schema onto a freshly-built
     * validation schema (matched by name). Only the OVERRIDE/empty path uses this — its field defs are new
     * objects, so mutating them is safe; MERGE/APPEND carry the dataset schema's own objects through.
     */
    private List<FieldDefinitionDto> applyScopeFromDataset(
            List<FieldDefinitionDto> schema, List<FieldDefinitionDto> datasetSchema) {
        if (datasetSchema == null || datasetSchema.isEmpty()) {
            return schema;
        }
        Map<String, Boolean> scopeByName = new LinkedHashMap<>();
        for (FieldDefinitionDto f : datasetSchema) {
            if (f != null && f.getName() != null) {
                scopeByName.put(f.getName(), f.getPerTurn());
            }
        }
        for (FieldDefinitionDto f : schema) {
            if (f != null && f.getName() != null && scopeByName.containsKey(f.getName())) {
                f.setPerTurn(scopeByName.get(f.getName()));
            }
        }
        return schema;
    }

    private List<FieldDefinitionDto> buildSchemaFromBindings(List<ColumnBinding> bindings) {
        List<FieldDefinitionDto> schema = new ArrayList<>();
        for (ColumnBinding b : bindings) {
            if (!"data".equals(b.mappedTo())) {
                continue;
            }
            // type=null: actual types are unknown during inline import/preview;
            // inference determines them later and the fixup pass corrects data
            schema.add(FieldDefinitionDto.builder()
                    .name(b.fieldName())
                    .type(null)
                    .required(false)
                    .build());
        }
        return schema;
    }

    private List<FieldDefinitionDto> mergeSchemaWithBindings(
            List<FieldDefinitionDto> existingSchema, List<ColumnBinding> bindings) {
        List<FieldDefinitionDto> merged = new ArrayList<>(existingSchema != null ? existingSchema : List.of());
        Set<String> existingNames = new LinkedHashSet<>();
        for (FieldDefinitionDto f : merged) {
            if (f != null && f.getName() != null) {
                existingNames.add(f.getName());
            }
        }
        for (ColumnBinding b : bindings) {
            if (!"data".equals(b.mappedTo()) || existingNames.contains(b.fieldName())) {
                continue;
            }
            // type=null: new column type unknown until inference completes
            merged.add(FieldDefinitionDto.builder()
                    .name(b.fieldName())
                    .type(null)
                    .required(false)
                    .build());
        }
        return merged;
    }

    // -------------------------------------------------------------------------
    // Schema persistence after streaming
    // -------------------------------------------------------------------------

    /**
     * Persists the dataset's test_case_schema for OVERRIDE / empty-schema / MERGE-with-new-fields cases.
     * Returns true when the dataset schema column was updated (which also bumps the dataset version).
     */
    private boolean persistSchema(
            UUID datasetId,
            CsvImportMode mode,
            boolean schemaEmpty,
            List<ColumnBinding> bindings,
            List<FieldDefinitionDto> testCaseSchema,
            Map<String, SchemaFieldType> inferredTypes) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            // Build full auto-detected schema from inferred types (OVERRIDE, APPEND+empty, MERGE+empty)
            List<FieldDefinitionDto> newSchema = buildSchemaFromInferred(bindings, inferredTypes);
            String schemaJson = serializeSchema(newSchema);
            datasetRepository.updateTestCaseSchema(datasetId, schemaJson);
            return true;
        }
        if (mode == CsvImportMode.MERGE && !inferredTypes.isEmpty()) {
            // Merge: add only new fields (those in inferredTypes not already in existing schema)
            Map<String, SchemaFieldType> existingTypes = getFieldTypes(testCaseSchema);
            List<FieldDefinitionDto> mergedSchema =
                    new ArrayList<>(testCaseSchema != null ? testCaseSchema : List.of());
            boolean anyNew = false;
            for (Map.Entry<String, SchemaFieldType> entry : inferredTypes.entrySet()) {
                if (!existingTypes.containsKey(entry.getKey())) {
                    mergedSchema.add(FieldDefinitionDto.builder()
                            .name(entry.getKey())
                            .type(entry.getValue())
                            .required(false)
                            .build());
                    anyNew = true;
                }
            }
            if (anyNew) {
                String schemaJson = serializeSchema(mergedSchema);
                datasetRepository.updateTestCaseSchema(datasetId, schemaJson);
                return true;
            }
        }
        // APPEND with existing schema: no schema update
        return false;
    }

    private List<FieldDefinitionDto> buildSchemaFromInferred(
            List<ColumnBinding> bindings, Map<String, SchemaFieldType> inferredTypes) {
        List<FieldDefinitionDto> schema = new ArrayList<>();
        for (ColumnBinding b : bindings) {
            if (!"data".equals(b.mappedTo())) {
                continue;
            }
            SchemaFieldType type = inferredTypes.getOrDefault(b.fieldName(), SchemaFieldType.STRING);
            schema.add(FieldDefinitionDto.builder()
                    .name(b.fieldName())
                    .type(type)
                    .required(false)
                    .build());
        }
        return schema;
    }

    private String serializeSchema(List<FieldDefinitionDto> schema) {
        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize schema", e);
        }
    }

    // -------------------------------------------------------------------------
    // Post-persist fixup pass
    // -------------------------------------------------------------------------

    /**
     * Computes which column names had their schema type newly determined during this import.
     * Returns an empty set if no fixup is needed.
     */
    private Set<String> computeChangedColumns(
            CsvImportMode mode,
            boolean schemaEmpty,
            Map<String, SchemaFieldType> originalFieldTypes,
            Map<String, SchemaFieldType> inferredTypes) {
        if (mode == CsvImportMode.APPEND && !schemaEmpty) {
            return Set.of();
        }
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            return new LinkedHashSet<>(inferredTypes.keySet());
        }
        // MERGE + non-empty schema: only new columns not in original schema
        Set<String> changed = new LinkedHashSet<>();
        for (String col : inferredTypes.keySet()) {
            if (!originalFieldTypes.containsKey(col)) {
                changed.add(col);
            }
        }
        return changed;
    }

    /**
     * Builds the final schema list after schema persistence, for use in fixup re-validation.
     */
    private List<FieldDefinitionDto> buildFinalSchema(
            CsvImportMode mode,
            boolean schemaEmpty,
            List<ColumnBinding> bindings,
            List<FieldDefinitionDto> testCaseSchema,
            Map<String, SchemaFieldType> inferredTypes) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            return buildSchemaFromInferred(bindings, inferredTypes);
        }
        // MERGE + non-empty schema: existing schema + new fields from inferredTypes
        Map<String, SchemaFieldType> existingTypes = getFieldTypes(testCaseSchema);
        List<FieldDefinitionDto> merged = new ArrayList<>(testCaseSchema != null ? testCaseSchema : List.of());
        for (Map.Entry<String, SchemaFieldType> entry : inferredTypes.entrySet()) {
            if (!existingTypes.containsKey(entry.getKey())) {
                merged.add(FieldDefinitionDto.builder()
                        .name(entry.getKey())
                        .type(entry.getValue())
                        .required(false)
                        .build());
            }
        }
        return merged;
    }

    /**
     * Re-reads all test cases for the dataset in batches, coerces changed columns to match
     * the newly determined schema types, re-validates, and batch-updates changed rows.
     */
    private void fixupTestCases(
            UUID datasetId,
            Set<String> changedColumns,
            Map<String, SchemaFieldType> inferredTypes,
            List<FieldDefinitionDto> finalSchema) {
        int batchSize = csvImportProperties.getBatchSize();
        int offset = 0;

        while (true) {
            List<TestCase> batch = testCaseRepository.findBatchByDatasetId(datasetId, offset, batchSize);
            if (batch.isEmpty()) {
                break;
            }

            List<TestCase> toUpdate = new ArrayList<>();
            for (TestCase tc : batch) {
                Map<String, Object> data = deserializeData(tc.getData());
                String originalDataJson = tc.getData();
                boolean dataChanged = false;

                for (String col : changedColumns) {
                    Object value = data.get(col);
                    if (value == null) {
                        continue;
                    }
                    SchemaFieldType targetType = inferredTypes.get(col);
                    if (targetType == null) {
                        continue;
                    }
                    Object coerced = schemaTypeCoercer.coerce(value, targetType);
                    if (!coerced.equals(value)) {
                        data.put(col, coerced);
                        dataChanged = true;
                    }
                }

                if (dataChanged) {
                    String newDataJson = warningsSerializer.serializeMap(data);
                    if (!newDataJson.equals(originalDataJson)) {
                        ValidationResult vr = testCaseValidationService.validateTestCase(
                                data, finalSchema, null, List.of(), false, datasetId);
                        tc.setData(newDataJson);
                        tc.setValid(vr.isValid());
                        tc.setValidationWarnings(warningsSerializer.serializeWarnings(vr.getWarnings()));
                        toUpdate.add(tc);
                    }
                }
            }

            if (!toUpdate.isEmpty()) {
                testCaseRepository.batchUpdate(toUpdate);
            }

            offset += batch.size();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeData(String dataJson) {
        if (dataJson == null || dataJson.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(dataJson, new TypeReference<>() {});
        } catch (JacksonException e) {
            log.warn("Failed to deserialize test case data for fixup: {}", e.getMessage(), e);
            return new LinkedHashMap<>();
        }
    }

    // -------------------------------------------------------------------------
    // Incremental type inference helpers
    // -------------------------------------------------------------------------

    /**
     * Updates inferredTypes map with cell types from this record (all data columns).
     */
    private void updateInferredTypes(
            CSVRecord record, List<ColumnBinding> bindings, Map<String, SchemaFieldType> inferredTypes) {
        for (int i = 0; i < bindings.size() && i < record.size(); i++) {
            ColumnBinding b = bindings.get(i);
            if (!"data".equals(b.mappedTo())) {
                continue;
            }
            String raw = record.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            SchemaFieldType cellType = inferCellType(raw.trim());
            SchemaFieldType current = inferredTypes.get(b.fieldName());
            if (current == null) {
                inferredTypes.put(b.fieldName(), cellType);
            } else if (current != cellType) {
                inferredTypes.put(b.fieldName(), widenType(current, cellType));
            }
        }
    }

    /**
     * Updates inferredTypes only for columns NOT already in the existing schema (for MERGE mode).
     */
    private void updateInferredTypesForNewFields(
            CSVRecord record,
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> fieldTypes,
            Map<String, SchemaFieldType> inferredTypes) {
        for (int i = 0; i < bindings.size() && i < record.size(); i++) {
            ColumnBinding b = bindings.get(i);
            if (!"data".equals(b.mappedTo()) || fieldTypes.containsKey(b.fieldName())) {
                continue;
            }
            String raw = record.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            SchemaFieldType cellType = inferCellType(raw.trim());
            SchemaFieldType current = inferredTypes.get(b.fieldName());
            if (current == null) {
                inferredTypes.put(b.fieldName(), cellType);
            } else if (current != cellType) {
                inferredTypes.put(b.fieldName(), widenType(current, cellType));
            }
        }
    }

    private boolean shouldAutoDetectSchema(CsvImportMode mode, boolean schemaEmpty) {
        return mode == CsvImportMode.OVERRIDE || schemaEmpty;
    }

    // -------------------------------------------------------------------------
    // Preview collision detection helpers
    // -------------------------------------------------------------------------

    private void addCollisionWarnings(
            UUID datasetId,
            List<String> allCsvNames,
            LinkedHashSet<String> seenInCsv,
            List<CsvImportWarningDto> warnings,
            CsvConflictStrategy conflictStrategy) {
        // Collect unique lower-case names that aren't within-CSV dups (first occurrence only)
        List<String> lowerNames = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : allCsvNames) {
            String lower = name.toLowerCase();
            if (seen.add(lower)) {
                lowerNames.add(lower);
            }
        }
        if (lowerNames.isEmpty()) {
            return;
        }
        // Find names that already exist in the DB
        List<String> existingNames =
                testCaseRepository.findExistingNamesByDatasetIdAndNamesLower(datasetId, lowerNames);
        if (existingNames.isEmpty()) {
            return;
        }
        Set<String> existingLower = new LinkedHashSet<>();
        for (String name : existingNames) {
            existingLower.add(name.toLowerCase());
        }
        // Walk through CSV rows in order and annotate first collision per name
        Set<String> annotated = new LinkedHashSet<>();
        int rowNum = 2; // header is row 1
        for (String name : allCsvNames) {
            String lower = name.toLowerCase();
            if (existingLower.contains(lower) && annotated.add(lower)) {
                String msg = buildCollisionWarningMessage(name, conflictStrategy);
                warnings.add(CsvImportWarningDto.builder()
                        .rowNumber(rowNum)
                        .columnName(TEST_CASE_NAME_HEADER)
                        .message(msg)
                        .build());
            }
            rowNum++;
        }
    }

    private static String buildCollisionWarningMessage(String name, CsvConflictStrategy strategy) {
        return switch (strategy) {
            case FAIL -> "would fail with 409 — collides with existing test case '" + name + "'";
            case SKIP -> "would be skipped — collides with existing test case '" + name + "'";
            case OVERRIDE -> "would override existing test case '" + name + "'";
        };
    }

    private static CsvImportWarningDto buildWithinCsvDupWarning(
            int rowNumber, String name, CsvConflictStrategy strategy) {
        String msg =
                switch (strategy) {
                    case FAIL -> "would cause import failure (409) — duplicate of earlier row with same name";
                    case SKIP -> "would be skipped — duplicate of earlier row with same name";
                    case OVERRIDE -> "would replace earlier row with same name (last wins)";
                };
        return CsvImportWarningDto.builder()
                .rowNumber(rowNumber)
                .columnName(TEST_CASE_NAME_HEADER)
                .message(msg)
                .build();
    }

    // -------------------------------------------------------------------------
    // Preview schema helpers
    // -------------------------------------------------------------------------

    private List<FieldDefinitionDto> buildAutoDetectedSchema(
            CsvImportMode mode,
            boolean schemaEmpty,
            List<ColumnBinding> bindings,
            List<FieldDefinitionDto> testCaseSchema,
            Map<String, SchemaFieldType> inferredTypes) {
        if (mode == CsvImportMode.OVERRIDE) {
            // Always return full auto-detected schema
            return buildSchemaFromInferred(bindings, inferredTypes);
        } else if (mode == CsvImportMode.APPEND) {
            if (schemaEmpty) {
                return buildSchemaFromInferred(bindings, inferredTypes);
            }
            return null;
        } else if (mode == CsvImportMode.MERGE) {
            // Return only delta fields (new columns not in existing schema)
            Map<String, SchemaFieldType> existingTypes = getFieldTypes(testCaseSchema);
            List<FieldDefinitionDto> delta = new ArrayList<>();
            for (ColumnBinding b : bindings) {
                if (!"data".equals(b.mappedTo()) || existingTypes.containsKey(b.fieldName())) {
                    continue;
                }
                SchemaFieldType type = inferredTypes.getOrDefault(b.fieldName(), SchemaFieldType.STRING);
                delta.add(FieldDefinitionDto.builder()
                        .name(b.fieldName())
                        .type(type)
                        .required(false)
                        .build());
            }
            return delta.isEmpty() ? null : delta;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // File / CSV parsing helpers
    // -------------------------------------------------------------------------

    private void validateFileSize(long contentLength) {
        long maxBytes = csvImportProperties.getMaxFileSize().toBytes();
        if (contentLength > maxBytes) {
            throw new ValidationException("File size exceeds limit " + csvImportProperties.getMaxFileSize());
        }
    }

    private static CSVParser createParser(InputStream inputStream, char delimiter) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setTrim(true)
                .setIgnoreEmptyLines(false)
                .get();
        InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        return CSVParser.builder().setFormat(format).setReader(reader).get();
    }

    private List<String> parseHeader(CSVParser parser) throws IOException {
        Iterator<CSVRecord> it = parser.iterator();
        if (!it.hasNext()) {
            return List.of();
        }
        CSVRecord first = it.next();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            headers.add(first.get(i).trim());
        }
        return headers;
    }

    /**
     * Maps CSV headers to column bindings. The only reserved header is {@code testCaseName};
     * every other column maps to a data field of the same name. (The legacy {@code enabled}
     * column is no longer reserved — if present, it is treated like any other data column.)
     */
    private List<ColumnBinding> resolveColumnBindings(List<String> headers) {
        List<ColumnBinding> bindings = new ArrayList<>();
        for (String header : headers) {
            String mappedTo;
            String fieldName;
            if (TEST_CASE_NAME_HEADER.equalsIgnoreCase(header)) {
                mappedTo = "testCaseName";
                fieldName = "testCaseName";
            } else if (TURN_INDEX_HEADER.equalsIgnoreCase(header)) {
                mappedTo = "turnIndex";
                fieldName = "turnIndex";
            } else {
                mappedTo = "data";
                fieldName = header;
            }
            bindings.add(new ColumnBinding(header, mappedTo, fieldName));
        }
        return bindings;
    }

    /**
     * Builds detected column info for preview response.
     * When auto-detected schema is available (schema was empty), uses auto-detected types.
     */
    private List<CsvColumnInfoDto> buildDetectedColumns(
            List<String> headers,
            List<ColumnBinding> bindings,
            Map<String, SchemaFieldType> fieldTypes,
            List<FieldDefinitionDto> autoDetectedSchema) {
        Map<String, SchemaFieldType> autoTypes = new LinkedHashMap<>();
        if (autoDetectedSchema != null) {
            for (FieldDefinitionDto f : autoDetectedSchema) {
                autoTypes.put(f.getName(), f.getType());
            }
        }

        List<CsvColumnInfoDto> result = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            ColumnBinding b = bindings.get(i);
            String inferredType;
            if ("data".equals(b.mappedTo())) {
                SchemaFieldType schemaType = fieldTypes.get(b.fieldName());
                if (schemaType != null) {
                    inferredType = schemaType.name();
                } else {
                    SchemaFieldType autoType = autoTypes.get(b.fieldName());
                    inferredType = autoType != null ? autoType.name() : "STRING";
                }
            } else {
                inferredType = "STRING";
            }
            result.add(CsvColumnInfoDto.builder()
                    .headerName(b.headerName())
                    .mappedTo(b.mappedTo())
                    .fieldName(b.fieldName())
                    .inferredType(inferredType)
                    .build());
        }
        return result;
    }

    /**
     * Extracts field types from testCaseSchema into a name-to-type map.
     */
    private Map<String, SchemaFieldType> getFieldTypes(List<FieldDefinitionDto> testCaseSchema) {
        if (testCaseSchema == null || testCaseSchema.isEmpty()) {
            return Map.of();
        }
        Map<String, SchemaFieldType> out = new LinkedHashMap<>();
        for (FieldDefinitionDto f : testCaseSchema) {
            if (f.getName() != null && f.getType() != null) {
                out.put(f.getName(), f.getType());
            }
        }
        return out;
    }

    /**
     * Parses a CSV record into a ParsedRow using the column bindings.
     * All non-reserved columns go into the single "data" map.
     * For OBJECT/ARRAY schema fields, attempts JSON parsing.
     * When mode==APPEND and schema is non-empty: unknown columns (not in fieldTypes) are discarded.
     */
    private ParsedRow parseRow(
            CSVRecord record,
            List<ColumnBinding> bindings,
            int rowNumber,
            int dataRowIndex,
            int padWidth,
            Map<String, SchemaFieldType> fieldTypes,
            CsvImportMode mode,
            boolean schemaEmpty) {
        String testCaseName = null;
        Integer turnIndex = null;
        Map<String, Object> data = new LinkedHashMap<>();
        boolean hasJsonParseErrors = false;

        for (int i = 0; i < bindings.size() && i < record.size(); i++) {
            ColumnBinding b = bindings.get(i);
            String raw = record.get(i);
            Object value = csvCellParser.parseCell(raw);

            switch (b.mappedTo()) {
                case "testCaseName" -> testCaseName = value != null ? value.toString() : null;
                case "turnIndex" -> turnIndex = parseTurnIndex(raw);
                case "data" -> {
                    if (b.fieldName() != null && !b.fieldName().isBlank()) {
                        // APPEND + non-empty schema: discard unknown columns
                        if (mode == CsvImportMode.APPEND && !schemaEmpty && !fieldTypes.containsKey(b.fieldName())) {
                            continue;
                        }
                        SchemaFieldType type = fieldTypes.get(b.fieldName());
                        if (type == SchemaFieldType.OBJECT || type == SchemaFieldType.ARRAY) {
                            Object parsed = parseJsonCell(raw, type);
                            if (parsed != null) {
                                data.put(b.fieldName(), parsed);
                            } else {
                                data.put(b.fieldName(), value);
                                hasJsonParseErrors = true;
                            }
                        } else if (type == null && raw != null && !raw.isBlank()) {
                            String trimmed = raw.trim();
                            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                                Object parsed = tryParseJsonValue(trimmed);
                                data.put(b.fieldName(), parsed != null ? parsed : value);
                            } else {
                                data.put(b.fieldName(), value);
                            }
                        } else {
                            data.put(b.fieldName(), schemaTypeCoercer.coerce(value, type));
                        }
                    }
                }
                default -> {}
            }
        }

        if (testCaseName == null || testCaseName.isBlank()) {
            testCaseName = String.format("Row %0" + padWidth + "d", dataRowIndex);
        }

        return new ParsedRow(rowNumber, testCaseName, turnIndex, data, hasJsonParseErrors);
    }

    /** Parses the reserved {@code turnIndex} ordering hint; blank → null (single-turn), non-int → null. */
    private Integer parseTurnIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("Non-integer turnIndex '{}', treating as blank: {}", raw, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parses a cell as JSON for OBJECT or ARRAY schema type. Returns null if parsing fails.
     */
    private Object parseJsonCell(String raw, SchemaFieldType type) {
        if (raw == null || raw.isBlank()) {
            return type == SchemaFieldType.ARRAY ? List.of() : Map.of();
        }
        try {
            return objectMapper.readValue(raw.trim(), Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Best-effort JSON parse for cells with unknown schema type.
     * Returns the parsed List/Map if the cell is valid JSON array/object, or null otherwise.
     */
    private Object tryParseJsonValue(String trimmedRaw) {
        try {
            Object parsed = objectMapper.readValue(trimmedRaw, Object.class);
            if (parsed instanceof List<?> || parsed instanceof Map<?, ?>) {
                return parsed;
            }
            return null;
        } catch (JacksonException e) {
            log.debug("Cell starting with [ or {{ is not valid JSON, falling back to string: {}", trimmedRaw, e);
            return null;
        }
    }

    private static ValidationResult combineWithJsonParseErrors(ValidationResult vr, ParsedRow row) {
        boolean valid = vr.isValid() && !row.hasJsonParseErrors();
        List<ValidationWarningDto> warnings = new ArrayList<>(vr.getWarnings() != null ? vr.getWarnings() : List.of());
        if (row.hasJsonParseErrors()) {
            warnings.add(ValidationWarningDto.builder()
                    .message("Cell could not be parsed as JSON for OBJECT/ARRAY field")
                    .code(ValidationWarningCode.UNKNOWN)
                    .build());
        }
        return ValidationResult.builder().valid(valid).warnings(warnings).build();
    }

    // -------------------------------------------------------------------------
    // Type inference
    // -------------------------------------------------------------------------

    /**
     * Infers the type of a single cell value, including OBJECT and ARRAY detection via JSON parsing.
     */
    private SchemaFieldType inferCellType(String value) {
        if (value.startsWith("{")) {
            try {
                objectMapper.readValue(value, Object.class);
                return SchemaFieldType.OBJECT;
            } catch (JacksonException ignored) {
                // not valid JSON object
            }
        }
        if (value.startsWith("[")) {
            try {
                objectMapper.readValue(value, Object.class);
                return SchemaFieldType.ARRAY;
            } catch (JacksonException ignored) {
                // not valid JSON array
            }
        }
        String lower = value.toLowerCase();
        if ("true".equals(lower) || "false".equals(lower)) {
            return SchemaFieldType.BOOLEAN;
        }
        if (value.matches("^-?\\d+$")) {
            return SchemaFieldType.INTEGER;
        }
        if (value.matches("^-?\\d+\\.?\\d*$")) {
            return SchemaFieldType.NUMBER;
        }
        return SchemaFieldType.STRING;
    }

    /**
     * Widens two different types to the broadest compatible type.
     * INTEGER + NUMBER -> NUMBER. Everything else mismatched -> STRING.
     */
    private static SchemaFieldType widenType(SchemaFieldType a, SchemaFieldType b) {
        if (a == b) {
            return a;
        }
        Set<SchemaFieldType> pair = Set.of(a, b);
        if (pair.equals(Set.of(SchemaFieldType.INTEGER, SchemaFieldType.NUMBER))) {
            return SchemaFieldType.NUMBER;
        }
        return SchemaFieldType.STRING;
    }

    // -------------------------------------------------------------------------
    // Conversion helpers
    // -------------------------------------------------------------------------

    private TestCaseResponseDto toResponseDto(ParsedRow row, ValidationResult vr) {
        return TestCaseResponseDto.builder()
                .id(null)
                .testCaseName(row.testCaseName())
                .data(row.data())
                .valid(vr.isValid())
                .validationWarnings(vr.getWarnings())
                .createdAt(null)
                .updatedAt(null)
                .build();
    }

    private TestCase toEntity(ParsedRow row, UUID datasetId, ValidationResult vr) {
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(row.testCaseName())
                .data(warningsSerializer.serializeMap(row.data()))
                .valid(vr.isValid())
                .validationWarnings(warningsSerializer.serializeWarnings(vr.getWarnings()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    private record ColumnBinding(String headerName, String mappedTo, String fieldName) {}

    private record ParsedRow(
            int rowNumber,
            String testCaseName,
            Integer turnIndex,
            Map<String, Object> data,
            boolean hasJsonParseErrors) {}

    private record InsertResult(int validCount, int invalidCount, int skippedCount, int overriddenCount) {}
}
