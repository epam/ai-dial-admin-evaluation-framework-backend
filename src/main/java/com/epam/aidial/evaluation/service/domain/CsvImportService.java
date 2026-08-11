package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.csv.ColumnBinding;
import com.epam.aidial.evaluation.service.domain.csv.CsvCellParser;
import com.epam.aidial.evaluation.service.domain.csv.CsvSchemaFieldBuilder;
import com.epam.aidial.evaluation.service.domain.csv.CsvTestCase;
import com.epam.aidial.evaluation.service.domain.csv.CsvTestCaseGrouper;
import com.epam.aidial.evaluation.service.domain.csv.MultiTurnAssembly;
import com.epam.aidial.evaluation.service.domain.csv.MultiTurnRunAssembler;
import com.epam.aidial.evaluation.service.domain.csv.ParsedCsvRow;
import com.epam.aidial.evaluation.service.domain.csv.SchemaTypeCoercer;
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
    private final TestCaseTurnsCsvSerializer turnsCsvSerializer;
    private final CsvSchemaFieldBuilder schemaFieldBuilder;
    private final CsvTestCaseGrouper csvTestCaseGrouper;
    private final MultiTurnRunAssembler multiTurnRunAssembler;
    private final DurableWarningMerger durableWarningMerger;

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
            Set<String> allDataFieldNames = allDataFieldNames(bindings);
            List<FieldDefinitionDto> validationSchema =
                    buildValidationSchema(mode, schemaEmpty, bindings, testCaseSchema, allDataFieldNames);

            List<CsvImportWarningDto> warnings = new ArrayList<>();
            List<TestCaseResponseDto> sampleRows = new ArrayList<>();
            int totalRows = 0;
            int totalTestCases = 0;
            int rowNum = 1;
            int padWidth = String.valueOf(csvImportProperties.getMaxRows()).length();
            // File-level multi-turn gate (design D3): true once any closed case is multi-turn.
            boolean sawMultiTurnCase = false;

            // Incremental type inference for preview schema detection
            Map<String, SchemaFieldType> inferredTypes = new LinkedHashMap<>();
            /*
             Within-CSV duplicate tracking, keyed on the assembled test case (one entry per multi-turn
             test case, one per single-turn row) — never per raw CSV row.
            */
            LinkedHashSet<String> seenNames = new LinkedHashSet<>();
            /*
             Assembled-case name occurrences for collision detection (APPEND/MERGE) and within-CSV dup
             warnings, one pair per registered name occurrence — no larger than allCsvNames was before.
            */
            List<NameOccurrence> occurrences = new ArrayList<>();

            /*
             Same accumulator-driven grouping as importCsv: a test case closes as soon as testCaseName
             changes, so preview never buffers more than the current test case's rows plus the per-name
             bookkeeping above.
            */
            CsvTestCaseGrouper.Accumulator testCaseAccumulator = csvTestCaseGrouper.newAccumulator();
            for (CSVRecord record : parser) {
                if (totalRows >= csvImportProperties.getMaxRows()) {
                    throw new ValidationException("Row count exceeds limit " + csvImportProperties.getMaxRows());
                }
                rowNum++;
                ParsedCsvRow row =
                        parseRow(record, bindings, rowNum, totalRows + 1, padWidth, fieldTypes, mode, schemaEmpty);
                totalRows++;

                // Incremental schema inference
                if (shouldAutoDetectSchema(mode, schemaEmpty)) {
                    updateInferredTypes(record, bindings, inferredTypes);
                } else if (mode == CsvImportMode.MERGE && !schemaEmpty) {
                    updateInferredTypesForNewFields(record, bindings, fieldTypes, inferredTypes);
                }

                CsvTestCase accumulatedTestCase = testCaseAccumulator.add(row);
                if (accumulatedTestCase != null) {
                    sawMultiTurnCase = sawMultiTurnCase || accumulatedTestCase.multiTurn();
                    totalTestCases += handlePreviewCase(
                            accumulatedTestCase,
                            datasetId,
                            validationSchema,
                            conflictStrategy,
                            seenNames,
                            occurrences,
                            warnings,
                            sampleRows);
                }
            }

            if (totalRows == 0) {
                throw new ValidationException("Empty CSV (header only, no data rows)");
            }

            // Process the final test case
            CsvTestCase finalCase = testCaseAccumulator.flush();
            if (finalCase != null) {
                sawMultiTurnCase = sawMultiTurnCase || finalCase.multiTurn();
                totalTestCases += handlePreviewCase(
                        finalCase,
                        datasetId,
                        validationSchema,
                        conflictStrategy,
                        seenNames,
                        occurrences,
                        warnings,
                        sampleRows);
            }

            // Cross-import collision detection for APPEND/MERGE (no DB query needed for OVERRIDE)
            if (mode != CsvImportMode.OVERRIDE && !occurrences.isEmpty()) {
                addCollisionWarnings(datasetId, occurrences, warnings, conflictStrategy);
            }

            // Post-stream membership set (design D3): the observed multi-turn gate, exact after streaming.
            Set<String> finalMultiTurnColumns = sawMultiTurnCase ? allDataFieldNames : Set.of();
            List<FieldDefinitionDto> autoDetectedSchema = buildAutoDetectedSchema(
                    mode, schemaEmpty, bindings, testCaseSchema, inferredTypes, finalMultiTurnColumns);

            List<CsvColumnInfoDto> detectedColumns =
                    buildDetectedColumns(headers, bindings, fieldTypes, autoDetectedSchema);

            return CsvImportPreviewDto.builder()
                    .detectedColumns(detectedColumns)
                    .totalRows(totalRows)
                    .totalTestCases(totalTestCases)
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
            Set<String> allDataFieldNames = allDataFieldNames(bindings);
            List<FieldDefinitionDto> validationSchema =
                    buildValidationSchema(mode, schemaEmpty, bindings, testCaseSchema, allDataFieldNames);

            List<CsvImportWarningDto> warnings = new ArrayList<>();
            int totalRows = 0;
            int validCount = 0;
            int invalidCount = 0;
            int skippedCount = 0;
            int overriddenCount = 0;
            int rowNum = 1;
            int maxRows = csvImportProperties.getMaxRows();
            int padWidth = String.valueOf(maxRows).length();
            // File-level multi-turn gate (design D3): true once any closed case is multi-turn.
            boolean sawMultiTurnCase = false;

            // Incremental type inference for schema detection
            Map<String, SchemaFieldType> inferredTypes = new LinkedHashMap<>();

            // OVERRIDE mode: delete all existing rows first
            if (mode == CsvImportMode.OVERRIDE) {
                testCaseRepository.deleteAllByDatasetId(datasetId, List.of());
            }

            CsvTestCaseGrouper.Accumulator testCaseAccumulator = csvTestCaseGrouper.newAccumulator();
            for (CSVRecord record : parser) {
                if (totalRows >= maxRows) {
                    throw new ValidationException("Row count exceeds limit " + maxRows);
                }
                rowNum++;
                ParsedCsvRow row =
                        parseRow(record, bindings, rowNum, totalRows + 1, padWidth, fieldTypes, mode, schemaEmpty);
                totalRows++;

                // Incremental schema inference per row
                if (shouldAutoDetectSchema(mode, schemaEmpty)) {
                    updateInferredTypes(record, bindings, inferredTypes);
                } else if (mode == CsvImportMode.MERGE && !schemaEmpty) {
                    updateInferredTypesForNewFields(record, bindings, fieldTypes, inferredTypes);
                }

                CsvTestCase accumulatedTestCase = testCaseAccumulator.add(row);
                if (accumulatedTestCase != null) {
                    sawMultiTurnCase = sawMultiTurnCase || accumulatedTestCase.multiTurn();
                    InsertResult result = processTestCase(
                            accumulatedTestCase, datasetId, validationSchema, conflictStrategy, warnings);
                    validCount += result.validCount();
                    invalidCount += result.invalidCount();
                    skippedCount += result.skippedCount();
                    overriddenCount += result.overriddenCount();
                }
            }

            if (totalRows == 0) {
                throw new ValidationException("Empty CSV (header only, no data rows)");
            }

            // Process the final test case
            CsvTestCase finalCase = testCaseAccumulator.flush();
            if (finalCase != null) {
                sawMultiTurnCase = sawMultiTurnCase || finalCase.multiTurn();
                InsertResult result =
                        processTestCase(finalCase, datasetId, validationSchema, conflictStrategy, warnings);
                validCount += result.validCount();
                invalidCount += result.invalidCount();
                skippedCount += result.skippedCount();
                overriddenCount += result.overriddenCount();
            }

            // Post-stream membership set (design D3): the observed multi-turn gate, exact after streaming.
            Set<String> finalMultiTurnColumns = sawMultiTurnCase ? allDataFieldNames : Set.of();

            // Schema persistence after streaming completes
            boolean schemaPersisted = persistSchema(
                    datasetId, mode, schemaEmpty, bindings, testCaseSchema, inferredTypes, finalMultiTurnColumns);

            // Post-persist fixup: coerce values for columns with newly determined types
            Set<String> changedColumns = computeChangedColumns(mode, schemaEmpty, fieldTypes, inferredTypes);
            if (!changedColumns.isEmpty()) {
                List<FieldDefinitionDto> finalSchema = buildFinalSchema(
                        mode, schemaEmpty, bindings, testCaseSchema, inferredTypes, finalMultiTurnColumns);
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
     * Processes one completed contiguous group of rows sharing a testCaseName: a single-turn case (one row,
     * blank turnIndex) or a multi-turn case (assembled into {@code multiTurnData}). A non-contiguous
     * reappearance of a multi-turn name — already detected by the grouper's accumulator — is reported as a
     * conflict warning.
     */
    private InsertResult processTestCase(
            CsvTestCase testCase,
            UUID datasetId,
            List<FieldDefinitionDto> testCaseSchema,
            CsvConflictStrategy conflictStrategy,
            List<CsvImportWarningDto> warnings) {
        if (!testCase.multiTurn()) {
            int validCount = 0;
            int invalidCount = 0;
            int skippedCount = 0;
            int overriddenCount = 0;
            for (ParsedCsvRow row : testCase.rows()) {
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

        addNonContiguousWarningIfNeeded(testCase, warnings);
        MultiTurnAssembly assembly = multiTurnRunAssembler.assemble(testCase, testCaseSchema);
        ValidationResult combined =
                validateTestCaseAsMultiTurn(testCase, assembly, datasetId, testCaseSchema, warnings);
        return persist(
                toMultiTurnEntity(testCase.testCaseName(), datasetId, assembly, combined),
                combined,
                conflictStrategy,
                testCase.testCaseName());
    }

    /**
     * Emits the non-contiguity conflict warning when {@code testCase} is a multi-turn name reappearing
     * after an earlier, already-completed multi-turn test case of the same name — the accumulator's
     * {@code nonContiguous} signal. Shared by import ({@link #processTestCase}) and preview ({@link
     * #handlePreviewCase}) so both report the identical warning for the identical condition.
     */
    private void addNonContiguousWarningIfNeeded(CsvTestCase testCase, List<CsvImportWarningDto> warnings) {
        if (testCase.nonContiguous()) {
            warnings.add(CsvImportWarningDto.builder()
                    .rowNumber(testCase.firstRowNumber())
                    .columnName(TEST_CASE_NAME_HEADER)
                    .message("Test case name '" + testCase.testCaseName()
                            + "' appears non-contiguously; multi-turn rows of a case must be contiguous")
                    .build());
        }
    }

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

    private ValidationResult validateTestCaseAsMultiTurn(
            CsvTestCase testCase,
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
                            + testCase.testCaseName() + "'; they must be identical")
                    .code(ValidationWarningCode.INVALID_INPUT)
                    .build());
        }
        if (assembly.hasJsonParseErrors()) {
            valid = false;
            merged.add(ValidationWarningDto.builder()
                    .message("Cell could not be parsed as JSON for OBJECT/ARRAY field")
                    .code(ValidationWarningCode.UNKNOWN)
                    .build());
        }
        if (assembly.duplicateTurnIndex()) {
            valid = false;
            merged.add(ValidationWarningDto.builder()
                    .fieldName(TURN_INDEX_HEADER)
                    .message("Duplicate turnIndex within multi-turn case '" + testCase.testCaseName() + "'")
                    .code(ValidationWarningCode.INVALID_INPUT)
                    .build());
        }
        ValidationResult combined =
                ValidationResult.builder().valid(valid).warnings(merged).build();
        collectWarnings(combined, testCase.firstRowNumber(), warnings);
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

    private TestCase toMultiTurnEntity(
            String testCaseName, UUID datasetId, MultiTurnAssembly assembly, ValidationResult vr) {
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(testCaseName)
                .data(warningsSerializer.serializeMap(assembly.sharedData()))
                .multiTurnData(turnsCsvSerializer.serializeTurns(assembly.perTurnMaps()))
                .valid(vr.isValid())
                .validationWarnings(warningsSerializer.serializeWarnings(vr.getWarnings()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Preview test case processing — mirrors processTestCase's assembly/validation, minus persistence
    // -------------------------------------------------------------------------

    /**
     * Previews one completed contiguous group of rows exactly as {@link #processTestCase} would import it:
     * a single-turn group of K rows assembles into K sample test cases (one name occurrence each); a
     * multi-turn group assembles into one sample test case (one name occurrence for the whole group)
     * carrying its {@code multiTurnData}. Returns the number of test cases this group assembles into, for
     * the response's {@code totalTestCases}. Registers one occurrence per assembled case for both
     * within-CSV duplicate detection and cross-import collision detection ({@link #addCollisionWarnings})
     * — the same "one group can assemble into several test cases" rule import's counters use.
     */
    private int handlePreviewCase(
            CsvTestCase testCase,
            UUID datasetId,
            List<FieldDefinitionDto> validationSchema,
            CsvConflictStrategy conflictStrategy,
            LinkedHashSet<String> seenNames,
            List<NameOccurrence> occurrences,
            List<CsvImportWarningDto> warnings,
            List<TestCaseResponseDto> sampleRows) {
        if (!testCase.multiTurn()) {
            for (ParsedCsvRow row : testCase.rows()) {
                registerOccurrence(
                        row.testCaseName(), row.rowNumber(), seenNames, occurrences, warnings, conflictStrategy);
                ValidationResult vr = testCaseValidationService.validateTestCase(
                        row.data(), validationSchema, null, List.of(), false, datasetId);
                ValidationResult combined = combineWithJsonParseErrors(vr, row);
                collectWarnings(combined, row.rowNumber(), warnings);
                if (sampleRows.size() < SAMPLE_ROWS_LIMIT) {
                    sampleRows.add(toResponseDto(row, combined));
                }
            }
            return testCase.rows().size();
        }

        registerOccurrence(
                testCase.testCaseName(), testCase.firstRowNumber(), seenNames, occurrences, warnings, conflictStrategy);
        addNonContiguousWarningIfNeeded(testCase, warnings);
        MultiTurnAssembly assembly = multiTurnRunAssembler.assemble(testCase, validationSchema);
        ValidationResult combined =
                validateTestCaseAsMultiTurn(testCase, assembly, datasetId, validationSchema, warnings);
        if (sampleRows.size() < SAMPLE_ROWS_LIMIT) {
            sampleRows.add(toMultiTurnResponseDto(testCase.testCaseName(), assembly, combined));
        }
        return 1;
    }

    /**
     * Registers one assembled-case name occurrence and, on a repeat (case-insensitive), emits the
     * within-CSV duplicate warning — the same check whether the occurrence came from a single-turn row or a
     * whole multi-turn test case.
     */
    private void registerOccurrence(
            String name,
            int rowNumber,
            LinkedHashSet<String> seenNames,
            List<NameOccurrence> occurrences,
            List<CsvImportWarningDto> warnings,
            CsvConflictStrategy conflictStrategy) {
        occurrences.add(new NameOccurrence(name, rowNumber));
        if (!seenNames.add(name.toLowerCase())) {
            warnings.add(buildWithinCsvDupWarning(rowNumber, name, conflictStrategy));
        }
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
            List<FieldDefinitionDto> testCaseSchema,
            Set<String> multiTurnColumns) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            // Types are unknown until inference completes. A declared field still carries perTurn forward
            // from testCaseSchema by field name (declared scope is a persistent dataset-schema property a
            // CSV never expresses). An undeclared column gets the D2 pre-stream over-approximation:
            // multiTurnColumns is every data-bound column name, so it is treated as per-turn during
            // streaming — harmless for single-turn cases (their validation never consults scope), and
            // correct for multi-turn cases, for which per-turn is the desired answer anyway.
            return schemaFieldBuilder.buildFromBindings(bindings, null, testCaseSchema, multiTurnColumns);
        }
        if (mode == CsvImportMode.MERGE) {
            List<FieldDefinitionDto> merged = new ArrayList<>(testCaseSchema != null ? testCaseSchema : List.of());
            merged.addAll(schemaFieldBuilder.buildMergeDelta(testCaseSchema, bindings, null, multiTurnColumns));
            return merged;
        }
        // APPEND + non-empty schema: use existing schema as-is
        return testCaseSchema != null ? testCaseSchema : List.of();
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
            Map<String, SchemaFieldType> inferredTypes,
            Set<String> multiTurnColumns) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            // Build full auto-detected schema from inferred types (OVERRIDE, APPEND+empty, MERGE+empty)
            List<FieldDefinitionDto> newSchema =
                    schemaFieldBuilder.buildFromBindings(bindings, inferredTypes, testCaseSchema, multiTurnColumns);
            String schemaJson = serializeSchema(newSchema);
            datasetRepository.updateTestCaseSchema(datasetId, schemaJson);
            return true;
        }
        if (mode == CsvImportMode.MERGE && !inferredTypes.isEmpty()) {
            // Merge: add only new fields (those not already in the existing schema)
            List<FieldDefinitionDto> delta =
                    schemaFieldBuilder.buildMergeDelta(testCaseSchema, bindings, inferredTypes, multiTurnColumns);
            if (!delta.isEmpty()) {
                List<FieldDefinitionDto> mergedSchema =
                        new ArrayList<>(testCaseSchema != null ? testCaseSchema : List.of());
                mergedSchema.addAll(delta);
                String schemaJson = serializeSchema(mergedSchema);
                datasetRepository.updateTestCaseSchema(datasetId, schemaJson);
                return true;
            }
        }
        // APPEND with existing schema: no schema update
        return false;
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
            Map<String, SchemaFieldType> inferredTypes,
            Set<String> multiTurnColumns) {
        if (mode == CsvImportMode.OVERRIDE || schemaEmpty) {
            return schemaFieldBuilder.buildFromBindings(bindings, inferredTypes, testCaseSchema, multiTurnColumns);
        }
        // MERGE + non-empty schema: existing schema + new fields from inferredTypes
        List<FieldDefinitionDto> merged = new ArrayList<>(testCaseSchema != null ? testCaseSchema : List.of());
        merged.addAll(schemaFieldBuilder.buildMergeDelta(testCaseSchema, bindings, inferredTypes, multiTurnColumns));
        return merged;
    }

    /**
     * Re-reads all test cases for the dataset in batches, coerces changed columns to match
     * the newly determined schema types, re-validates, and batch-updates changed rows.
     *
     * <p>Branches on the row's <b>raw stored</b> {@code multi_turn_data} column being non-blank — not on
     * whether it deserializes — so a case whose turns cannot be read is routed to {@link
     * #fixupMultiTurnCase} rather than silently treated as single-turn (which would overwrite it with a
     * {@code null} turn array and destroy every turn).
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
                String rawTurns = tc.getMultiTurnData();
                if (rawTurns != null && !rawTurns.isBlank()) {
                    fixupMultiTurnCase(datasetId, tc, rawTurns, changedColumns, inferredTypes, finalSchema, toUpdate);
                } else {
                    fixupSingleTurnCase(datasetId, tc, changedColumns, inferredTypes, finalSchema, toUpdate);
                }
            }

            if (!toUpdate.isEmpty()) {
                testCaseRepository.batchUpdate(toUpdate);
            }

            offset += batch.size();
        }
    }

    /**
     * Single-turn fixup: coercion/re-validation logic unchanged from before the multi-turn branch was
     * added. Per design D8, the durable-warning merger is applied to every recomputation pass, not only
     * the multi-turn one — a single-turn case never carries a stored {@code INVALID_INPUT} warning
     * today, so this is a no-op ({@code warningsSerializer.serializeWarnings} returns {@code "[]"} for
     * both {@code null} and an empty list, matching what was written before), but stating the rule once
     * and applying it everywhere is the point: a future path must not be able to quietly skip it the way
     * {@code buildSchemaFromInferred} quietly skipped {@code perTurn} carry-forward.
     */
    private void fixupSingleTurnCase(
            UUID datasetId,
            TestCase tc,
            Set<String> changedColumns,
            Map<String, SchemaFieldType> inferredTypes,
            List<FieldDefinitionDto> finalSchema,
            List<TestCase> toUpdate) {
        Map<String, Object> data = deserializeData(tc.getData());
        String originalDataJson = tc.getData();

        if (!coerceColumns(data, changedColumns, inferredTypes)) {
            return;
        }

        String newDataJson = warningsSerializer.serializeMap(data);
        if (newDataJson.equals(originalDataJson)) {
            return;
        }

        ValidationResult vr =
                testCaseValidationService.validateTestCase(data, finalSchema, null, List.of(), false, datasetId);
        ValidationResult merged = durableWarningMerger.merge(vr, tc.getValidationWarnings());
        tc.setData(newDataJson);
        tc.setValid(merged.isValid());
        tc.setValidationWarnings(warningsSerializer.serializeWarnings(merged.getWarnings()));
        toUpdate.add(tc);
    }

    /**
     * Multi-turn fixup (design D6): coerces changed columns inside shared {@code data} as well as inside
     * every turn map, re-validates via {@link TestCaseValidationService#validateMultiTurn} against the
     * <b>full</b> schema (it splits by scope internally), and carries forward any stored durable
     * ({@code INVALID_INPUT}) warning via {@link DurableWarningMerger} before writing (design D8) — a
     * plain recomputation would otherwise erase the import's own conflict verdict.
     *
     * <p>The row's raw {@code multi_turn_data} is read with {@link
     * TestCaseTurnsCsvSerializer#deserializeTurnsStrict}, which throws on unreadable JSON instead of
     * collapsing it to {@code null} the way the lenient {@code deserializeTurns} does. A row whose turns
     * cannot be read is skipped entirely — never added to {@code toUpdate} — because writing {@code null}
     * back would convert the case to single-turn and destroy every turn: a worse version of the bug this
     * pass exists to fix.
     */
    private void fixupMultiTurnCase(
            UUID datasetId,
            TestCase tc,
            String rawTurns,
            Set<String> changedColumns,
            Map<String, SchemaFieldType> inferredTypes,
            List<FieldDefinitionDto> finalSchema,
            List<TestCase> toUpdate) {
        List<Map<String, Object>> turns;
        try {
            turns = turnsCsvSerializer.deserializeTurnsStrict(rawTurns);
        } catch (JacksonException e) {
            log.warn(
                    "Skipping test case {} during CSV import fixup: stored multi_turn_data is unreadable, "
                            + "leaving it untouched to avoid destroying its turns: {}",
                    tc.getId(),
                    e.getMessage(),
                    e);
            return;
        }

        if (turns == null) {
            // The raw column is non-blank but parses to the JSON literal `null` (deserializeTurnsStrict
            // returns null for this input, same as for an absent column). Re-serializing an empty list here
            // would silently overwrite it with "[]" — a shape change this pass must not make — so fall back
            // to the single-turn path, which leaves tc.getMultiTurnData() untouched and writes it back
            // verbatim via batchUpdate.
            fixupSingleTurnCase(datasetId, tc, changedColumns, inferredTypes, finalSchema, toUpdate);
            return;
        }

        Map<String, Object> sharedData = deserializeData(tc.getData());
        String originalDataJson = tc.getData();

        boolean changed = coerceColumns(sharedData, changedColumns, inferredTypes);
        for (Map<String, Object> turn : turns) {
            if (turn != null && coerceColumns(turn, changedColumns, inferredTypes)) {
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        String newDataJson = warningsSerializer.serializeMap(sharedData);
        String newTurnsJson = turnsCsvSerializer.serializeTurns(turns);
        if (newDataJson.equals(originalDataJson) && rawTurns.equals(newTurnsJson)) {
            return;
        }

        ValidationResult recomputed = testCaseValidationService.validateMultiTurn(
                sharedData, turns, finalSchema, null, List.of(), false, datasetId);
        ValidationResult merged = durableWarningMerger.merge(recomputed, tc.getValidationWarnings());

        tc.setData(newDataJson);
        tc.setMultiTurnData(newTurnsJson);
        tc.setValid(merged.isValid());
        tc.setValidationWarnings(warningsSerializer.serializeWarnings(merged.getWarnings()));
        toUpdate.add(tc);
    }

    /**
     * Coerces every {@code changedColumns} entry present in {@code data} to its newly inferred type,
     * mutating {@code data} in place. Shared by the single-turn path (on {@code data}) and the multi-turn
     * path (on shared {@code data} and on each turn map). Returns whether anything actually changed.
     */
    private boolean coerceColumns(
            Map<String, Object> data, Set<String> changedColumns, Map<String, SchemaFieldType> inferredTypes) {
        boolean changed = false;
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
                changed = true;
            }
        }
        return changed;
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
            if (!ColumnBinding.MAPPED_TO_DATA.equals(b.mappedTo())) {
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
            if (!ColumnBinding.MAPPED_TO_DATA.equals(b.mappedTo()) || fieldTypes.containsKey(b.fieldName())) {
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

    /**
     * Annotates the first CSV occurrence of each assembled-case name that collides with an existing test
     * case in the dataset (APPEND/MERGE cross-import collision). Consumes {@code (caseName, rowNumber)}
     * pairs keyed on the assembled test case — one per multi-turn test case, one per single-turn row —
     * rather than a parallel per-row name list, so a colliding multi-turn case is annotated once, at its
     * test case's first row number.
     */
    private void addCollisionWarnings(
            UUID datasetId,
            List<NameOccurrence> occurrences,
            List<CsvImportWarningDto> warnings,
            CsvConflictStrategy conflictStrategy) {
        // Collect unique lower-case names that aren't within-CSV dups (first occurrence only)
        List<String> lowerNames = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NameOccurrence occurrence : occurrences) {
            String lower = occurrence.name().toLowerCase();
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
        // Walk through the occurrences in order and annotate the first collision per name
        Set<String> annotated = new LinkedHashSet<>();
        for (NameOccurrence occurrence : occurrences) {
            String lower = occurrence.name().toLowerCase();
            if (existingLower.contains(lower) && annotated.add(lower)) {
                String msg = buildCollisionWarningMessage(occurrence.name(), conflictStrategy);
                warnings.add(CsvImportWarningDto.builder()
                        .rowNumber(occurrence.rowNumber())
                        .columnName(TEST_CASE_NAME_HEADER)
                        .message(msg)
                        .build());
            }
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
            Map<String, SchemaFieldType> inferredTypes,
            Set<String> multiTurnColumns) {
        if (mode == CsvImportMode.OVERRIDE) {
            // Always return full auto-detected schema
            return schemaFieldBuilder.buildFromBindings(bindings, inferredTypes, testCaseSchema, multiTurnColumns);
        } else if (mode == CsvImportMode.APPEND) {
            if (schemaEmpty) {
                return schemaFieldBuilder.buildFromBindings(bindings, inferredTypes, testCaseSchema, multiTurnColumns);
            }
            return null;
        } else if (mode == CsvImportMode.MERGE) {
            // Return only delta fields (new columns not in existing schema)
            List<FieldDefinitionDto> delta =
                    schemaFieldBuilder.buildMergeDelta(testCaseSchema, bindings, inferredTypes, multiTurnColumns);
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
                mappedTo = ColumnBinding.MAPPED_TO_TEST_CASE_NAME;
                fieldName = ColumnBinding.MAPPED_TO_TEST_CASE_NAME;
            } else if (TURN_INDEX_HEADER.equalsIgnoreCase(header)) {
                mappedTo = ColumnBinding.MAPPED_TO_TURN_INDEX;
                fieldName = ColumnBinding.MAPPED_TO_TURN_INDEX;
            } else {
                mappedTo = ColumnBinding.MAPPED_TO_DATA;
                fieldName = header;
            }
            bindings.add(new ColumnBinding(header, mappedTo, fieldName));
        }
        return bindings;
    }

    /**
     * Collects every data-bound (MAPPED_TO_DATA) field name from {@code bindings} — the multi-turn scope
     * over-approximation (design D2) used to build the pre-stream validation schema, and the exact
     * membership set (design D3) used to build every post-stream schema when the file contains at least
     * one multi-turn case.
     */
    private static Set<String> allDataFieldNames(List<ColumnBinding> bindings) {
        Set<String> names = new LinkedHashSet<>();
        for (ColumnBinding binding : bindings) {
            if (ColumnBinding.MAPPED_TO_DATA.equals(binding.mappedTo())) {
                names.add(binding.fieldName());
            }
        }
        return names;
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
            if (ColumnBinding.MAPPED_TO_DATA.equals(b.mappedTo())) {
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
     * Parses a CSV record into a ParsedCsvRow using the column bindings.
     * All non-reserved columns go into the single "data" map.
     * For OBJECT/ARRAY schema fields, attempts JSON parsing.
     * When mode==APPEND and schema is non-empty: unknown columns (not in fieldTypes) are discarded.
     */
    private ParsedCsvRow parseRow(
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
                case ColumnBinding.MAPPED_TO_TEST_CASE_NAME -> testCaseName = value != null ? value.toString() : null;
                case ColumnBinding.MAPPED_TO_TURN_INDEX -> turnIndex = parseTurnIndex(raw);
                case ColumnBinding.MAPPED_TO_DATA -> {
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

        return new ParsedCsvRow(rowNumber, testCaseName, turnIndex, data, hasJsonParseErrors);
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

    private static ValidationResult combineWithJsonParseErrors(ValidationResult vr, ParsedCsvRow row) {
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

    private TestCaseResponseDto toResponseDto(ParsedCsvRow row, ValidationResult vr) {
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

    private TestCase toEntity(ParsedCsvRow row, UUID datasetId, ValidationResult vr) {
        return TestCase.builder()
                .datasetId(datasetId)
                .testCaseName(row.testCaseName())
                .data(warningsSerializer.serializeMap(row.data()))
                .valid(vr.isValid())
                .validationWarnings(warningsSerializer.serializeWarnings(vr.getWarnings()))
                .build();
    }

    /** Preview's sample-row counterpart to {@link #toMultiTurnEntity}: an assembled case, not persisted. */
    private TestCaseResponseDto toMultiTurnResponseDto(
            String testCaseName, MultiTurnAssembly assembly, ValidationResult vr) {
        return TestCaseResponseDto.builder()
                .id(null)
                .testCaseName(testCaseName)
                .data(assembly.sharedData())
                .multiTurnData(assembly.perTurnMaps())
                .valid(vr.isValid())
                .validationWarnings(vr.getWarnings())
                .createdAt(null)
                .updatedAt(null)
                .build();
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    private record InsertResult(int validCount, int invalidCount, int skippedCount, int overriddenCount) {}

    /** One registered assembled-case name occurrence: the case's name and its first CSV row number. */
    private record NameOccurrence(String name, int rowNumber) {}
}
