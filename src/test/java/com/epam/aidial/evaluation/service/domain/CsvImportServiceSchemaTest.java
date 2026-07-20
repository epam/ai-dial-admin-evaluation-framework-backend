package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.service.domain.csv.CsvCellParser;
import com.epam.aidial.evaluation.service.domain.csv.SchemaTypeCoercer;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("CsvImportService — schema merge and column filtering")
@ExtendWith(MockitoExtension.class)
class CsvImportServiceSchemaTest {

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private RevalidationService revalidationService;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private TestCaseValidationService testCaseValidationService;

    @Mock
    private CsvImportProperties csvImportProperties;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    private CsvImportService service;
    private UUID datasetId;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CsvCellParser csvCellParser = new CsvCellParser();
        SchemaTypeCoercer schemaTypeCoercer = new SchemaTypeCoercer();
        service = new CsvImportService(
                datasetRepository,
                datasetSchemaProvider,
                testCaseRepository,
                testCaseValidationService,
                revalidationService,
                csvImportProperties,
                csvCellParser,
                schemaTypeCoercer,
                objectMapper,
                warningsSerializer,
                new MultiTurnFieldsValidator());
        datasetId = UUID.randomUUID();

        when(csvImportProperties.getMaxFileSize()).thenReturn(DataSize.ofMegabytes(10));
        when(csvImportProperties.getMaxRows()).thenReturn(10000);
        when(csvImportProperties.getBatchSize()).thenReturn(100);
        when(testCaseValidationService.validateTestCase(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        when(warningsSerializer.serializeWarnings(any())).thenReturn("[]");
        when(warningsSerializer.serializeMap(any())).thenReturn("{}");
        lenient().when(testCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Task 8.2 — Schema merge logic
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE mode: always replaces schema with auto-detected from CSV (even if schema was non-empty)")
    void overrideModeAlwaysReplacesSchema() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"oldField\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        String csv = "testCaseName,newField\nRow1,hello";
        importCsv(csv, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetRepository).updateTestCaseSchema(eq(datasetId), schemaCaptor.capture());
        assertThat(schemaCaptor.getValue()).contains("newField");
        assertThat(schemaCaptor.getValue()).doesNotContain("oldField");
    }

    @Test
    @DisplayName("APPEND mode with empty schema: auto-detects and persists schema")
    void appendEmptySchemaAutoDetects() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,score\nRow1,42";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetRepository).updateTestCaseSchema(eq(datasetId), schemaCaptor.capture());
        assertThat(schemaCaptor.getValue()).contains("score");
    }

    @Test
    @DisplayName("APPEND mode with existing schema: no schema update")
    void appendExistingSchemaNoUpdate() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,unknownCol\nRow1,hello,extra";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        verify(datasetRepository, never()).updateTestCaseSchema(any(), any());
    }

    @Test
    @DisplayName("MERGE mode: new columns are added to schema, updateTestCaseSchema called")
    void mergeModeAddsNewColumns() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,newField\nRow1,hello,world";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetRepository).updateTestCaseSchema(eq(datasetId), schemaCaptor.capture());
        assertThat(schemaCaptor.getValue()).contains("prompt");
        assertThat(schemaCaptor.getValue()).contains("newField");
    }

    @Test
    @DisplayName("MERGE mode: no new columns — schema NOT updated (version not bumped)")
    void mergeModeNoNewColumnsSkipsUpdate() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        verify(datasetRepository, never()).updateTestCaseSchema(any(), any());
    }

    @Test
    @DisplayName("MERGE mode with empty schema: auto-detects all columns and persists schema")
    void mergeModeEmptySchemaAutoDetects() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,score,label\nRow1,42,good";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetRepository).updateTestCaseSchema(eq(datasetId), schemaCaptor.capture());
        assertThat(schemaCaptor.getValue()).contains("score");
        assertThat(schemaCaptor.getValue()).contains("label");
    }

    // -------------------------------------------------------------------------
    // Task 8.3 — parseRow column filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("APPEND + non-empty schema: unknown CSV columns are NOT stored in data")
    void appendWithSchemaDiscardsUnknownColumns() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,unknownCol\nRow1,hello,should_be_discarded";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.captor();
        verify(warningsSerializer).serializeMap(mapCaptor.capture());
        java.util.Map<String, Object> data = mapCaptor.getValue();
        assertThat(data).containsKey("prompt");
        assertThat(data).doesNotContainKey("unknownCol");
    }

    @Test
    @DisplayName("reserved multiTurnId/turnIndex columns parse to top-level fields, not into data")
    void reservedMultiTurnColumnsParseToTopLevelFields() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        UUID multiTurnId = UUID.randomUUID();
        String csv = "testCaseName,multiTurnId,turnIndex,prompt\n" + "conv turn 0," + multiTurnId + ",0,hello";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        TestCase saved = captor.getValue();
        assertThat(saved.getMultiTurnId()).isEqualTo(multiTurnId);
        assertThat(saved.getTurnIndex()).isEqualTo(0);

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.captor();
        verify(warningsSerializer).serializeMap(mapCaptor.capture());
        Map<String, Object> data = mapCaptor.getValue();
        assertThat(data).containsKey("prompt");
        assertThat(data).doesNotContainKey("multiTurnId");
        assertThat(data).doesNotContainKey("turnIndex");
    }

    @Test
    @DisplayName("APPEND + empty schema: all CSV columns ARE stored in data")
    void appendEmptySchemaKeepsAllColumns() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,col1,col2\nRow1,v1,v2";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.captor();
        verify(warningsSerializer).serializeMap(mapCaptor.capture());
        java.util.Map<String, Object> data = mapCaptor.getValue();
        assertThat(data).containsKey("col1");
        assertThat(data).containsKey("col2");
    }

    @Test
    @DisplayName("MERGE + non-empty schema: new CSV columns ARE stored in data (not discarded)")
    void mergeStoresNewColumnsInData() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,newField\nRow1,hello,world";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.captor();
        verify(warningsSerializer).serializeMap(mapCaptor.capture());
        java.util.Map<String, Object> data = mapCaptor.getValue();
        assertThat(data).containsKey("prompt");
        assertThat(data).containsKey("newField");
    }

    @Test
    @DisplayName("OVERRIDE: all CSV columns ARE stored in data")
    void overrideStoresAllColumns() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(1L);

        String csv = "testCaseName,prompt,extra\nRow1,hello,world";
        importCsv(csv, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        var mapCaptor = ArgumentCaptor.<Map<String, Object>>captor();
        verify(warningsSerializer).serializeMap(mapCaptor.capture());
        java.util.Map<String, Object> data = mapCaptor.getValue();
        assertThat(data).containsKey("prompt");
        assertThat(data).containsKey("extra");
    }

    // -------------------------------------------------------------------------
    // Task 8.5 — Within-CSV collision behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SKIP strategy + within-CSV duplicate: first row kept, skippedCount=1")
    void skipStrategyWithinCsvDupFirstWins() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        // First insert returns 1 (inserted), second returns 0 (skipped)
        when(testCaseRepository.insertOrSkip(any())).thenReturn(1).thenReturn(0);

        String csv = "testCaseName\nDuplicateName\nduplicatename";
        CsvImportResultDto result = importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.SKIP);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getOverriddenCount()).isNull();
    }

    @Test
    @DisplayName("OVERRIDE conflict strategy + within-CSV duplicate: last wins, overriddenCount=1")
    void overrideStrategyWithinCsvDupLastWins() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        // First upsert: new insert (false), second upsert: replaces (true)
        when(testCaseRepository.insertOrOverride(any())).thenReturn(false).thenReturn(true);

        String csv = "testCaseName\nDuplicateName\nduplicatename";
        CsvImportResultDto result = importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.OVERRIDE);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getOverriddenCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isNull();
    }

    @Test
    @DisplayName("skippedCount and overriddenCount are null when conflictStrategy=FAIL")
    void failStrategyCountsAreNull() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName\nRow1";
        CsvImportResultDto result = importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        assertThat(result.getSkippedCount()).isNull();
        assertThat(result.getOverriddenCount()).isNull();
    }

    // -------------------------------------------------------------------------
    // Validation schema: correct target schema used during import
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE + non-empty schema: validation uses CSV-derived schema, not old schema")
    void overrideWithExistingSchemaValidatesAgainstCsvHeaders() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"oldField\",\"type\":\"STRING\",\"required\":true}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        String csv = "testCaseName,newField\nRow1,hello";
        importCsv(csv, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).hasSize(1);
        assertThat(validationSchema.getFirst().getName()).isEqualTo("newField");
        assertThat(validationSchema.getFirst().isRequired()).isFalse();
    }

    @Test
    @DisplayName("OVERRIDE + empty schema: validation uses CSV-derived schema")
    void overrideWithEmptySchemaValidatesAgainstCsvHeaders() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        String csv = "testCaseName,col1,col2\nRow1,a,b";
        importCsv(csv, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).extracting(FieldDefinitionDto::getName).containsExactly("col1", "col2");
        assertThat(validationSchema).allMatch(f -> !f.isRequired());
    }

    @Test
    @DisplayName("MERGE + non-empty schema with new columns: validation uses merged schema")
    void mergeWithNewColumnsValidatesAgainstMergedSchema() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":true}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,newMetric\nRow1,hello,5";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).extracting(FieldDefinitionDto::getName).containsExactly("prompt", "newMetric");
        // Existing field retains required=true
        assertThat(validationSchema.getFirst().isRequired()).isTrue();
        // New field is required=false
        assertThat(validationSchema.get(1).isRequired()).isFalse();
    }

    @Test
    @DisplayName("MERGE + non-empty schema, no new columns: validation uses existing schema")
    void mergeNoNewColumnsValidatesAgainstExistingSchema() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":true}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).hasSize(1);
        assertThat(validationSchema.getFirst().getName()).isEqualTo("prompt");
        assertThat(validationSchema.getFirst().isRequired()).isTrue();
    }

    @Test
    @DisplayName("MERGE + empty schema: validation uses CSV-derived schema")
    void mergeEmptySchemaValidatesAgainstCsvHeaders() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,score,label\nRow1,42,good";
        importCsv(csv, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).extracting(FieldDefinitionDto::getName).containsExactly("score", "label");
        assertThat(validationSchema).allMatch(f -> !f.isRequired());
    }

    @Test
    @DisplayName("APPEND + empty schema: validation uses CSV-derived schema")
    void appendEmptySchemaValidatesAgainstCsvHeaders() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,col1\nRow1,v1";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).hasSize(1);
        assertThat(validationSchema.getFirst().getName()).isEqualTo("col1");
        assertThat(validationSchema.getFirst().isRequired()).isFalse();
    }

    @Test
    @DisplayName("APPEND + non-empty schema: validation uses existing schema unchanged")
    void appendExistingSchemaValidatesAgainstExistingSchema() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":true}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = captureValidationSchema();
        List<FieldDefinitionDto> validationSchema = schemaCaptor.getValue();
        assertThat(validationSchema).hasSize(1);
        assertThat(validationSchema.getFirst().getName()).isEqualTo("prompt");
        assertThat(validationSchema.getFirst().isRequired()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<FieldDefinitionDto>> captureValidationSchema() {
        ArgumentCaptor<List<FieldDefinitionDto>> schemaCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseValidationService)
                .validateTestCase(any(), schemaCaptor.capture(), any(), any(), anyBoolean(), any());
        return schemaCaptor;
    }

    // -------------------------------------------------------------------------
    // Post-persist fixup pass
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Fixup: empty schema with mixed types (INTEGER widened to STRING) coerces integers to strings")
    void fixupCoercesValuesWhenSchemaWidensToString() throws Exception {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        // Override serializeMap to produce real JSON (needed for fixup data comparison)
        when(warningsSerializer.serializeMap(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) inv.getArgument(0);
            if (map == null || map.isEmpty()) {
                return "{}";
            }
            return new ObjectMapper().writeValueAsString(map);
        });

        // Simulate stored test case with heuristic Long in a STRING-widened column
        TestCase storedTc = TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName("Row 01")
                .data("{\"col1\":42}")
                .valid(true)
                .validationWarnings("[]")
                .build();
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(storedTc));
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        // CSV: column has integer then string → widens to STRING
        String csv = "testCaseName,col1\nRow1,42\nRow2,hello";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        // Verify fixup: batchUpdate called with coerced data (integer → string "42")
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).batchUpdate(batchCaptor.capture());
        List<TestCase> updated = batchCaptor.getValue();
        assertThat(updated).hasSize(1);
        assertThat(updated.getFirst().getData()).contains("\"42\"");
    }

    @Test
    @DisplayName("APPEND + existing schema: no fixup pass runs")
    void appendExistingSchemaNoFixup() throws Exception {
        Dataset dataset = datasetWithSchema("[{\"name\":\"col1\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,col1\nRow1,42";
        importCsv(csv, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        verify(testCaseRepository, never()).findBatchByDatasetId(any(), anyInt(), anyInt());
        verify(testCaseRepository, never()).batchUpdate(anyList());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CsvImportResultDto importCsv(String csv, CsvImportMode mode, CsvConflictStrategy strategy) {
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return service.importCsv(datasetId, is, csv.length(), ',', null, mode, strategy);
    }

    private Dataset datasetWithSchema(String schemaJson) {
        Dataset dataset = new Dataset();
        dataset.setId(datasetId);
        dataset.setVersion(0L);
        dataset.setTestCaseSchema(schemaJson);
        List<FieldDefinitionDto> parsed;
        try {
            parsed = objectMapper.readValue(schemaJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid schema json in test fixture: " + schemaJson, e);
        }
        lenient().when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(parsed);
        return dataset;
    }
}
