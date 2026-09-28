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
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.csv.CsvCellParser;
import com.epam.aidial.evaluation.service.domain.csv.CsvImportSchemaHints;
import com.epam.aidial.evaluation.service.domain.csv.CsvSchemaFieldBuilder;
import com.epam.aidial.evaluation.service.domain.csv.CsvTestCaseGrouper;
import com.epam.aidial.evaluation.service.domain.csv.MultiTurnRunAssembler;
import com.epam.aidial.evaluation.service.domain.csv.SchemaTypeCoercer;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/**
 * Task group 3 (support-rich-zip-test-case-import): {@link CsvImportSchemaHints} wiring into {@link
 * CsvImportService}, covering design D4's mode table and the tier-0 manifest scope rule (multi-turn-test-case
 * spec). Plain-CSV (no-hints) behaviour is covered separately by {@link CsvImportServiceSchemaTest} and is
 * unaffected by this class's changes, since every overload here delegates through the hints-aware method
 * with {@link CsvImportSchemaHints#EMPTY} when hints are omitted.
 */
@DisplayName("CsvImportService — CsvImportSchemaHints (manifest + FILE hint)")
@ExtendWith(MockitoExtension.class)
class CsvImportServiceSchemaHintsTest {

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

    @Mock
    private TestCaseTurnsCsvSerializer turnsCsvSerializer;

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
                turnsCsvSerializer,
                new CsvSchemaFieldBuilder(),
                new CsvTestCaseGrouper(),
                new MultiTurnRunAssembler(new TestCaseFieldScopeResolver()),
                new DurableWarningMerger(warningsSerializer));
        datasetId = UUID.randomUUID();

        when(csvImportProperties.getMaxFileSize()).thenReturn(DataSize.ofMegabytes(10));
        when(csvImportProperties.getMaxRows()).thenReturn(10000);
        lenient().when(csvImportProperties.getBatchSize()).thenReturn(100);
        lenient()
                .when(testCaseValidationService.validateTestCase(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        lenient()
                .when(testCaseValidationService.validateMultiTurn(
                        any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
        lenient().when(warningsSerializer.serializeWarnings(any())).thenReturn("[]");
        lenient().when(warningsSerializer.serializeMap(any())).thenReturn("{}");
        lenient().when(warningsSerializer.deserializeWarnings(any())).thenReturn(List.of());
        lenient()
                .when(turnsCsvSerializer.serializeTurns(any()))
                .thenAnswer(
                        inv -> inv.getArgument(0) == null ? null : objectMapper.writeValueAsString(inv.getArgument(0)));
        lenient().when(testCaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CsvImportResultDto importCsv(String csv, CsvImportMode mode, CsvImportSchemaHints hints) {
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        return service.importCsv(datasetId, is, csv.length(), ',', null, mode, CsvConflictStrategy.FAIL, hints);
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

    private List<FieldDefinitionDto> persistedSchema() {
        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(datasetRepository)
                .updateTestCaseSchema(org.mockito.ArgumentMatchers.eq(datasetId), schemaCaptor.capture());
        try {
            return objectMapper.readValue(schemaCaptor.getValue(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private FieldDefinitionDto fieldNamed(List<FieldDefinitionDto> schema, String name) {
        return schema.stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field not found: " + name));
    }

    // -------------------------------------------------------------------------
    // Task 3.2 — manifest declared tier across every mode (design D4)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE + manifest: manifest-listed field persists verbatim, including FILE type, "
            + "required, displayName and description")
    void overrideWithManifestPersistsManifestFieldVerbatim() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        FieldDefinitionDto manifestDocument = FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .required(true)
                .displayName("Document")
                .description("The uploaded document")
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestDocument), Set.of());

        String csv = "testCaseName,document\nRow1,@ef/datasets/x/report.pdf";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        FieldDefinitionDto persisted = fieldNamed(persistedSchema(), "document");
        assertThat(persisted.getType()).isEqualTo(SchemaFieldType.FILE);
        assertThat(persisted.isRequired()).isTrue();
        assertThat(persisted.getDisplayName()).isEqualTo("Document");
        assertThat(persisted.getDescription()).isEqualTo("The uploaded document");
    }

    @Test
    @DisplayName("OVERRIDE + manifest: a CSV column the manifest does not list is still inferred")
    void overrideWithManifestInfersUnlistedColumn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        FieldDefinitionDto manifestDocument = FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestDocument), Set.of());

        String csv = "testCaseName,document,score\nRow1,@ef/datasets/x/report.pdf,42";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        List<FieldDefinitionDto> persisted = persistedSchema();
        assertThat(fieldNamed(persisted, "score").getType()).isEqualTo(SchemaFieldType.INTEGER);
    }

    @Test
    @DisplayName("OVERRIDE + manifest: a manifest field absent from the CSV is still persisted")
    void overrideWithManifestKeepsFieldWithNoCsvColumn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        FieldDefinitionDto manifestExtra = FieldDefinitionDto.builder()
                .name("extra")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestExtra), Set.of());

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        assertThat(persistedSchema()).extracting(FieldDefinitionDto::getName).contains("prompt", "extra");
    }

    @Test
    @DisplayName("MERGE + manifest: a new field's definition comes from the manifest, existing fields unaffected")
    void mergeWithManifestTakesNewFieldFromManifest() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        FieldDefinitionDto manifestAttachment = FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .displayName("Attachment")
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestAttachment), Set.of());

        String csv = "testCaseName,prompt,attachment\nRow1,hello,@ef/datasets/x/a.pdf";
        importCsv(csv, CsvImportMode.MERGE, hints);

        List<FieldDefinitionDto> persisted = persistedSchema();
        FieldDefinitionDto attachment = fieldNamed(persisted, "attachment");
        assertThat(attachment.getType()).isEqualTo(SchemaFieldType.FILE);
        assertThat(attachment.isRequired()).isTrue();
        assertThat(attachment.getDisplayName()).isEqualTo("Attachment");
        FieldDefinitionDto prompt = fieldNamed(persisted, "prompt");
        assertThat(prompt.getType()).isEqualTo(SchemaFieldType.STRING);
    }

    @Test
    @DisplayName("APPEND + non-empty schema + manifest: manifest ignored entirely, schema not updated")
    void appendWithManifestIgnoresManifest() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        FieldDefinitionDto manifestAttachment = FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestAttachment), Set.of());

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.APPEND, hints);

        verify(datasetRepository, never()).updateTestCaseSchema(any(), any());
    }

    // -------------------------------------------------------------------------
    // Task 3.3 — manifest perTurn wins over the multi-turn file-level gate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Multi-turn CSV + manifest into empty schema: a manifest-declared shared column stays "
            + "shared, is stored in shared data, and reports no shared-column conflict")
    void manifestKeepsSharedColumnSharedInMultiTurnImport() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        FieldDefinitionDto manifestContext = FieldDefinitionDto.builder()
                .name("context")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build();
        FieldDefinitionDto manifestPrompt = FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestContext, manifestPrompt), Set.of());

        String csv = "testCaseName,turnIndex,context,prompt\n" + "Conv1,0,shared-ctx,hi\n" + "Conv1,1,shared-ctx,hello";
        CsvImportResultDto result = importCsv(csv, CsvImportMode.OVERRIDE, hints);

        assertThat(result.getInvalidCount()).isZero();
        assertThat(result.getWarnings())
                .noneMatch(w -> w.getMessage() != null && w.getMessage().contains("conflict"));

        List<FieldDefinitionDto> persisted = persistedSchema();
        assertThat(fieldNamed(persisted, "context").getPerTurn()).isNull();
        assertThat(fieldNamed(persisted, "prompt").getPerTurn()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 3.4 — fileColumns hint (no manifest) types a legacy column FILE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No manifest: a legacy-style CSV column named in fileColumns is persisted as FILE")
    void fileColumnsHintTypesLegacyColumnAsFile() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);

        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(), Set.of("document"));

        String csv = "testCaseName,document\nRow1,@ef/datasets/x/report.pdf";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        assertThat(fieldNamed(persistedSchema(), "document").getType()).isEqualTo(SchemaFieldType.FILE);
    }

    @Test
    @DisplayName("No manifest: a column already declared in the dataset schema is unaffected by fileColumns")
    void fileColumnsHintDoesNotAffectAlreadyDeclaredColumn() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        // "prompt" is already declared in the dataset schema; APPEND + non-empty schema never re-derives it,
        // regardless of the fileColumns hint.
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(), Set.of("prompt"));

        String csv = "testCaseName,prompt\nRow1,hello";
        importCsv(csv, CsvImportMode.APPEND, hints);

        verify(datasetRepository, never()).updateTestCaseSchema(any(), any());
    }

    @Test
    @DisplayName("Preview: no manifest, fileColumns hint types autoDetectedSchema column as FILE")
    void previewFileColumnsHintTypesColumnAsFile() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.existsById(datasetId)).thenReturn(true);

        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(), Set.of("document"));
        String csv = "testCaseName,document\nRow1,@ef/datasets/x/report.pdf";
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        CsvImportPreviewDto preview = service.preview(
                datasetId, is, csv.length(), ',', CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, hints);

        assertThat(preview.getAutoDetectedSchema()).isNotNull();
        assertThat(fieldNamed(preview.getAutoDetectedSchema(), "document").getType())
                .isEqualTo(SchemaFieldType.FILE);
    }

    // -------------------------------------------------------------------------
    // Correction round 1 — fixup coerces to the *effective* (manifest-aware) type, not the raw
    // per-cell inference (bug: a manifest-declared STRING column holding numeric-/boolean-looking
    // values was coerced to the inferred INTEGER/BOOLEAN type instead of staying a string)
    // -------------------------------------------------------------------------

    /** Makes {@code warningsSerializer.serializeMap} produce real JSON instead of the default "{}" stub. */
    private void useRealMapSerialization() {
        when(warningsSerializer.serializeMap(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) inv.getArgument(0);
            if (map == null || map.isEmpty()) {
                return "{}";
            }
            try {
                return objectMapper.writeValueAsString(map);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Map<String, Object> turn(Object code, Object flag) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("flag", flag);
        return t;
    }

    @Test
    @DisplayName("OVERRIDE + manifest, single-turn: a STRING-declared column holding a numeric or "
            + "boolean-looking value is coerced to a string, not to the raw inferred INTEGER/BOOLEAN type; "
            + "an INTEGER-declared column matching the natural inference is unaffected")
    void fixupCoercesToManifestTypeNotInferredTypeSingleTurn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestCode = FieldDefinitionDto.builder()
                .name("code")
                .type(SchemaFieldType.STRING)
                .build();
        FieldDefinitionDto manifestFlag = FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .build();
        FieldDefinitionDto manifestScore = FieldDefinitionDto.builder()
                .name("score")
                .type(SchemaFieldType.INTEGER)
                .build();
        CsvImportSchemaHints hints =
                new CsvImportSchemaHints(List.of(manifestCode, manifestFlag, manifestScore), Set.of());

        // Simulates the row as parse-time provisional storage would have left it: the pre-import schema was
        // empty, so csvCellParser's heuristic parsing stored a Long and a Boolean, not strings.
        TestCase storedTc = TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName("Row1")
                .data("{\"code\":42,\"flag\":true,\"score\":42}")
                .valid(true)
                .validationWarnings("[]")
                .build();
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(storedTc));
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        String csv = "testCaseName,code,flag,score\nRow1,42,true,42";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).batchUpdate(batchCaptor.capture());
        String updatedData = batchCaptor.getValue().getFirst().getData();
        assertThat(updatedData).contains("\"code\":\"42\"");
        assertThat(updatedData).contains("\"flag\":\"true\"");
        assertThat(updatedData).contains("\"score\":42").doesNotContain("\"score\":\"42\"");
    }

    @Test
    @DisplayName("OVERRIDE + manifest, multi-turn: a STRING-declared per-turn column holding numeric or "
            + "boolean-looking per-turn values is coerced to a string in every turn map, not to the raw "
            + "inferred INTEGER/BOOLEAN type")
    void fixupCoercesToManifestTypeNotInferredTypeMultiTurn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestCode = FieldDefinitionDto.builder()
                .name("code")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();
        FieldDefinitionDto manifestFlag = FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestCode, manifestFlag), Set.of());

        String storedTurnsJson = "[{\"code\":42,\"flag\":true},{\"code\":43,\"flag\":false}]";
        when(turnsCsvSerializer.deserializeTurnsStrict(storedTurnsJson))
                .thenReturn(List.of(turn(42L, true), turn(43L, false)));

        TestCase storedTc = TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName("Conv1")
                .data("{}")
                .multiTurnData(storedTurnsJson)
                .valid(true)
                .validationWarnings("[]")
                .build();
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(storedTc));
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        String csv = "testCaseName,turnIndex,code,flag\n" + "Conv1,0,42,true\n" + "Conv1,1,43,false";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).batchUpdate(batchCaptor.capture());
        String updatedTurns = batchCaptor.getValue().getFirst().getMultiTurnData();
        assertThat(updatedTurns)
                .contains("\"code\":\"42\"")
                .contains("\"code\":\"43\"")
                .contains("\"flag\":\"true\"")
                .contains("\"flag\":\"false\"");
    }

    // -------------------------------------------------------------------------
    // Correction round 2 — fixup reparses a manifest-declared OBJECT/ARRAY column whose value is still a
    // raw JSON-shaped String (the only route there: plain inference never yields OBJECT/ARRAY, and a
    // column already declared OBJECT/ARRAY in the pre-import schema is already parsed at parse time)
    // -------------------------------------------------------------------------

    /** RFC4180-quotes a raw value for embedding as one CSV cell (wraps in quotes, doubles internal quotes). */
    private static String csvQuote(String raw) {
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    @Test
    @DisplayName("OVERRIDE + manifest, single-turn: a manifest-declared OBJECT/ARRAY column whose "
            + "pre-fixup stored value is still a raw JSON string (the pre-import schema declared it "
            + "STRING) is reparsed into a JSON object/array; a manifest STRING column holding JSON-shaped "
            + "text stays a plain string")
    @SuppressWarnings("unchecked")
    void fixupReparsesManifestObjectAndArrayColumnsSingleTurn() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"meta\",\"type\":\"STRING\",\"required\":false},"
                + "{\"name\":\"tags\",\"type\":\"STRING\",\"required\":false},"
                + "{\"name\":\"note\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestMeta = FieldDefinitionDto.builder()
                .name("meta")
                .type(SchemaFieldType.OBJECT)
                .build();
        FieldDefinitionDto manifestTags = FieldDefinitionDto.builder()
                .name("tags")
                .type(SchemaFieldType.ARRAY)
                .build();
        FieldDefinitionDto manifestNote = FieldDefinitionDto.builder()
                .name("note")
                .type(SchemaFieldType.STRING)
                .build();
        CsvImportSchemaHints hints =
                new CsvImportSchemaHints(List.of(manifestMeta, manifestTags, manifestNote), Set.of());

        // Simulates parse-time provisional storage: the pre-import schema declared these STRING, so
        // parse-time's coercion path stored the raw JSON text as a plain string, not a parsed object/array.
        Map<String, Object> provisional = new LinkedHashMap<>();
        provisional.put("meta", "{\"k\":\"v\"}");
        provisional.put("tags", "[\"a\",\"b\"]");
        provisional.put("note", "{\"a\":1}");
        TestCase storedTc = TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName("Row1")
                .data(objectMapper.writeValueAsString(provisional))
                .valid(true)
                .validationWarnings("[]")
                .build();
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(storedTc));
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        String csv = "testCaseName,meta,tags,note\nRow1," + csvQuote("{\"k\":\"v\"}") + "," + csvQuote("[\"a\",\"b\"]")
                + "," + csvQuote("{\"a\":1}");
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).batchUpdate(batchCaptor.capture());
        Map<String, Object> updated = readData(batchCaptor.getValue().getFirst().getData());

        assertThat(updated.get("meta")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) updated.get("meta")).containsEntry("k", "v");
        assertThat(updated.get("tags")).isInstanceOf(List.class);
        assertThat((List<Object>) updated.get("tags")).containsExactly("a", "b");
        assertThat(updated.get("note")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("OVERRIDE + manifest, multi-turn: a manifest-declared per-turn OBJECT column whose "
            + "pre-fixup stored value is still a raw JSON string is reparsed into a JSON object in every "
            + "turn map")
    @SuppressWarnings("unchecked")
    void fixupReparsesManifestObjectColumnMultiTurn() {
        Dataset dataset =
                datasetWithSchema("[{\"name\":\"meta\",\"type\":\"STRING\",\"required\":false,\"perTurn\":true}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestMeta = FieldDefinitionDto.builder()
                .name("meta")
                .type(SchemaFieldType.OBJECT)
                .perTurn(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestMeta), Set.of());

        String storedTurnsJson = objectMapper.writeValueAsString(
                List.of(Map.of("meta", "{\"k\":\"v0\"}"), Map.of("meta", "{\"k\":\"v1\"}")));
        when(turnsCsvSerializer.deserializeTurnsStrict(storedTurnsJson))
                .thenReturn(List.of(turnOf("{\"k\":\"v0\"}"), turnOf("{\"k\":\"v1\"}")));

        TestCase storedTc = TestCase.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .testCaseName("Conv1")
                .data("{}")
                .multiTurnData(storedTurnsJson)
                .valid(true)
                .validationWarnings("[]")
                .build();
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(0), anyInt()))
                .thenReturn(List.of(storedTc));
        when(testCaseRepository.findBatchByDatasetId(eq(datasetId), eq(1), anyInt()))
                .thenReturn(List.of());

        String csv = "testCaseName,turnIndex,meta\n" + "Conv1,0," + csvQuote("{\"k\":\"v0\"}") + "\n" + "Conv1,1,"
                + csvQuote("{\"k\":\"v1\"}");
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestCase>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(testCaseRepository).batchUpdate(batchCaptor.capture());
        List<Map<String, Object>> updatedTurns =
                readTurns(batchCaptor.getValue().getFirst().getMultiTurnData());

        assertThat(updatedTurns).hasSize(2);
        assertThat(updatedTurns.get(0).get("meta")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) updatedTurns.get(0).get("meta")).containsEntry("k", "v0");
        assertThat(updatedTurns.get(1).get("meta")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) updatedTurns.get(1).get("meta")).containsEntry("k", "v1");
    }

    private static Map<String, Object> turnOf(String metaRawJsonText) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("meta", metaRawJsonText);
        return t;
    }

    private Map<String, Object> readData(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> readTurns(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Correction round 3 — parse-time types must honor the manifest wherever the mode lets it decide a
    // column, so a STRING (or FILE) column is coerced from the cell's raw text, never from
    // CsvCellParser's numeric/boolean heuristic guess (which the fixup pass can no longer recover from
    // once the original text is gone)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE + manifest, single-turn: STRING-declared columns are stored with the raw cell "
            + "text verbatim ('007', '1.50', 'TRUE'), not the heuristically inferred INTEGER/NUMBER/BOOLEAN "
            + "value; an undeclared numeric column still infers INTEGER")
    void manifestStringColumnsPreserveRawTextSingleTurn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestCode = FieldDefinitionDto.builder()
                .name("code")
                .type(SchemaFieldType.STRING)
                .build();
        FieldDefinitionDto manifestPrice = FieldDefinitionDto.builder()
                .name("price")
                .type(SchemaFieldType.STRING)
                .build();
        FieldDefinitionDto manifestFlag = FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .build();
        CsvImportSchemaHints hints =
                new CsvImportSchemaHints(List.of(manifestCode, manifestPrice, manifestFlag), Set.of());

        String csv = "testCaseName,code,price,flag,count\nRow1,007,1.50,TRUE,42";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        Map<String, Object> data = readData(captor.getValue().getData());

        assertThat(data)
                .containsEntry("code", "007")
                .containsEntry("price", "1.50")
                .containsEntry("flag", "TRUE");
        // Undeclared column: plain-CSV numeric inference is unaffected (Jackson round-trips a small JSON
        // integer as Integer, not Long, when deserialized back into a generic Map<String, Object>).
        assertThat(data.get("count")).isEqualTo(42);
        assertThat(fieldNamed(persistedSchema(), "count").getType()).isEqualTo(SchemaFieldType.INTEGER);
    }

    @Test
    @DisplayName("OVERRIDE + manifest, multi-turn: a STRING-declared per-turn column is stored with the raw "
            + "cell text verbatim in every turn map")
    void manifestStringColumnsPreserveRawTextMultiTurn() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        FieldDefinitionDto manifestCode = FieldDefinitionDto.builder()
                .name("code")
                .type(SchemaFieldType.STRING)
                .perTurn(true)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestCode), Set.of());

        String csv = "testCaseName,turnIndex,code\n" + "Conv1,0,007\n" + "Conv1,1,1.50";
        importCsv(csv, CsvImportMode.OVERRIDE, hints);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        List<Map<String, Object>> turns = readTurns(captor.getValue().getMultiTurnData());

        assertThat(turns).hasSize(2);
        assertThat(turns.get(0)).containsEntry("code", "007");
        assertThat(turns.get(1)).containsEntry("code", "1.50");
    }

    @Test
    @DisplayName("Preview: OVERRIDE + manifest STRING columns show the raw cell text in the sample row, "
            + "matching what import would store")
    void previewShowsRawTextForManifestStringColumns() {
        Dataset dataset = datasetWithSchema("[]");
        when(datasetRepository.existsById(datasetId)).thenReturn(true);

        FieldDefinitionDto manifestCode = FieldDefinitionDto.builder()
                .name("code")
                .type(SchemaFieldType.STRING)
                .build();
        FieldDefinitionDto manifestFlag = FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestCode, manifestFlag), Set.of());

        String csv = "testCaseName,code,flag\nRow1,007,TRUE";
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        CsvImportPreviewDto preview = service.preview(
                datasetId, is, csv.length(), ',', CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, hints);

        assertThat(preview.getSampleRows()).hasSize(1);
        TestCaseResponseDto sample = preview.getSampleRows().getFirst();
        assertThat(sample.getData()).containsEntry("code", "007").containsEntry("flag", "TRUE");
    }

    // -------------------------------------------------------------------------
    // Correction round 4 — the raw-text coercion path must be gated to manifest-driven columns only.
    // Plain CSV (EMPTY hints) into an already-declared field must keep byte-for-byte today's behaviour,
    // heuristic precision loss included: changing that is explicitly out of scope (proposal Non-goals).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Plain CSV (no hints), APPEND into an existing STRING-declared field: '007' still stores "
            + "the old heuristically-coerced value '7', not the raw text — this is unrelated to the "
            + "manifest fix and must not change")
    void plainCsvAppendIntoExistingStringFieldKeepsOldLossyValue() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"code\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        useRealMapSerialization();

        String csv = "testCaseName,code\nRow1,007";
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        service.importCsv(datasetId, is, csv.length(), ',', null, CsvImportMode.APPEND, CsvConflictStrategy.FAIL);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        Map<String, Object> data = readData(captor.getValue().getData());

        // Old, unchanged behaviour: the heuristic parser already turned "007" into Long(7) before any
        // type-aware coercion ran, so coercing it back to STRING yields "7", losing the leading zero.
        assertThat(data).containsEntry("code", "7");
    }

    @Test
    @DisplayName("Plain CSV (no hints), OVERRIDE into a dataset whose schema declares an OBJECT field: the "
            + "cell is still parsed into a JSON object exactly as before — parseFieldTypes must not null "
            + "out an existing declared type for a non-manifest-driven OVERRIDE import")
    @SuppressWarnings("unchecked")
    void plainCsvOverrideIntoExistingObjectFieldParsesJsonAsBefore() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"meta\",\"type\":\"OBJECT\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        when(testCaseRepository.deleteAllByDatasetId(any(), anyList())).thenReturn(0L);
        useRealMapSerialization();

        String csv = "testCaseName,meta\nRow1," + csvQuote("{\"k\":\"v\"}");
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        service.importCsv(datasetId, is, csv.length(), ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        ArgumentCaptor<TestCase> captor = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(captor.capture());
        Map<String, Object> data = readData(captor.getValue().getData());

        // Old, unchanged behaviour: "meta" was already declared OBJECT, so parseRow's OBJECT/ARRAY branch
        // parses the cell into a real JSON object at parse time — never a raw/undeclared-column string.
        assertThat(data.get("meta")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) data.get("meta")).containsEntry("k", "v");
    }

    // -------------------------------------------------------------------------
    // Correction round 6 — persistSchema's MERGE branch must still append a manifest-declared new field
    // whose cells are all blank (e.g. every referenced ZIP file missing from the archive): inferredTypes
    // never gets an entry for an all-blank column, so the old "!inferredTypes.isEmpty()" gate alone would
    // silently skip the manifest field entirely, contradicting the spec (MERGE takes a new field's
    // definition from the manifest, regardless of its cells).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MERGE + manifest: a new FILE column with all-blank cells is still appended with the "
            + "manifest's definition")
    void mergeAppendsManifestFieldEvenWhenAllCellsBlank() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        FieldDefinitionDto manifestAttachment = FieldDefinitionDto.builder()
                .name("attachment")
                .type(SchemaFieldType.FILE)
                .required(true)
                .displayName("Attachment")
                .build();
        CsvImportSchemaHints hints = new CsvImportSchemaHints(List.of(manifestAttachment), Set.of());

        String csv = "testCaseName,prompt,attachment\nRow1,hello,";
        importCsv(csv, CsvImportMode.MERGE, hints);

        List<FieldDefinitionDto> persisted = persistedSchema();
        FieldDefinitionDto attachment = fieldNamed(persisted, "attachment");
        assertThat(attachment.getType()).isEqualTo(SchemaFieldType.FILE);
        assertThat(attachment.isRequired()).isTrue();
        assertThat(attachment.getDisplayName()).isEqualTo("Attachment");
        // The existing field is untouched.
        assertThat(fieldNamed(persisted, "prompt").getType()).isEqualTo(SchemaFieldType.STRING);
    }

    @Test
    @DisplayName("Plain CSV (no hints), MERGE: a new column with all-blank cells is still never appended — "
            + "unchanged from before the manifest fix")
    void plainCsvMergeDoesNotAppendAllBlankNewColumn() {
        Dataset dataset = datasetWithSchema("[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":false}]");
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));

        String csv = "testCaseName,prompt,attachment\nRow1,hello,";
        InputStream is = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        service.importCsv(datasetId, is, csv.length(), ',', null, CsvImportMode.MERGE, CsvConflictStrategy.FAIL);

        verify(datasetRepository, never()).updateTestCaseSchema(any(), any());
    }
}
