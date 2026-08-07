package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RevalidationStatus;
import com.epam.aidial.evaluation.runner.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.type.TypeReference;

/**
 * Functional tests for the flat CSV multiplication of multi-turn cases: export emits one row per turn
 * (turnIndex {@code 0..N-1}; blank for single-turn), import assembles a contiguous run of same-named rows
 * carrying a turnIndex into one {@code multiTurnData} case, round-trips losslessly, and reports
 * non-contiguous names / duplicate turnIndex as conflicts.
 */
@DisplayName("Multi-turn CSV Functional Tests")
public abstract class MultiTurnCsvFunctionalTests extends AbstractMultiTurnFunctionalTest {

    @Autowired
    private TestCaseRepository testCaseRepository;

    private UUID promptDataset() {
        return newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(true)
                .perTurn(true)
                .build()));
    }

    @Test
    @DisplayName("Export emits one row per turn (turnIndex 0..N-1) and a blank turnIndex for single-turn")
    void exportMultipliesTurns() throws IOException {
        UUID datasetId = promptDataset();
        createMultiTurnCase(datasetId, "mt", List.of(Map.of("prompt", "a"), Map.of("prompt", "b")));
        createSingleTurnCase(datasetId, "st", Map.of("prompt", "c"));

        String csv = exportCsv(datasetId);
        List<CSVRecord> records = parseCsv(csv);

        // 3 data rows: 2 for the multi-turn case + 1 for the single-turn case.
        assertThat(records).hasSize(3);
        assertThat(records.get(0).isMapped("turnIndex")).isTrue();

        List<CSVRecord> mtRows =
                records.stream().filter(r -> "mt".equals(r.get("testCaseName"))).toList();
        assertThat(mtRows).hasSize(2);
        assertThat(mtRows.stream().map(r -> r.get("turnIndex"))).containsExactlyInAnyOrder("0", "1");
        assertThat(mtRows.stream().map(r -> r.get("prompt"))).containsExactlyInAnyOrder("a", "b");

        CSVRecord stRow = records.stream()
                .filter(r -> "st".equals(r.get("testCaseName")))
                .findFirst()
                .orElseThrow();
        assertThat(stRow.get("turnIndex")).isEmpty();
        assertThat(stRow.get("prompt")).isEqualTo("c");
    }

    @Test
    @DisplayName("Import assembles a contiguous run of same-named rows with turnIndex into one multi-turn case")
    void importAssemblesMultiTurn() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\nconv,0,hello\nconv,1,world";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(1);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.get(0);
        assertThat(conv.getTestCaseName()).isEqualTo("conv");
        assertThat(conv.getMultiTurnData()).hasSize(2);
        assertThat(conv.getMultiTurnData().get(0).get("prompt")).isEqualTo("hello");
        assertThat(conv.getMultiTurnData().get(1).get("prompt")).isEqualTo("world");
        assertThat(conv.getData()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Export then import round-trips a multi-turn case preserving its turns")
    void roundTripPreservesTurns() {
        UUID sourceDataset = promptDataset();
        createMultiTurnCase(sourceDataset, "conv", List.of(Map.of("prompt", "first"), Map.of("prompt", "second")));

        String csv = exportCsv(sourceDataset);

        UUID targetDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> importResponse = importCsv(targetDataset, csv, null);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody().getTotalRows()).isEqualTo(2);
        assertThat(importResponse.getBody().getValidCount()).isEqualTo(1);

        List<TestCaseResponseDto> cases = listTestCases(targetDataset);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.get(0);
        assertThat(conv.getMultiTurnData()).hasSize(2);
        assertThat(conv.getMultiTurnData().stream().map(t -> t.get("prompt"))).containsExactly("first", "second");
    }

    @Test
    @DisplayName("Duplicate turnIndex within a case is marked invalid with a conflict warning")
    void duplicateTurnIndexRejected() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,a\ndup,0,b";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);
        assertThat(response.getBody().getWarnings().stream().map(w -> w.getMessage()))
                .anyMatch(msg -> msg.contains("Duplicate turnIndex"));
    }

    @Test
    @DisplayName("A name reappearing non-contiguously is reported as a conflict warning")
    void nonContiguousNameRejected() {
        UUID datasetId = promptDataset();
        // "conv" turn 0, then a different case, then "conv" turn 1 — the second "conv" run is non-contiguous.
        String csv = "testCaseName,turnIndex,prompt\nconv,0,a\nother,0,x\nconv,1,b";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWarnings().stream().map(w -> w.getMessage()))
                .anyMatch(msg -> msg.contains("non-contiguously"));
    }

    @Test
    @DisplayName("A multi-turn run does not collide with itself under FAIL, SKIP, or OVERRIDE")
    void multiTurnRunDoesNotCollideWithItself() {
        String csv = "testCaseName,turnIndex,prompt\nconv,0,hello\nconv,1,world";

        UUID failDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> failResponse = importCsv(failDataset, csv, "FAIL");
        assertThat(failResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listTestCases(failDataset)).hasSize(1);

        UUID skipDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> skipResponse = importCsv(skipDataset, csv, "SKIP");
        assertThat(skipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(skipResponse.getBody()).isNotNull();
        assertThat(skipResponse.getBody().getSkippedCount()).isIn(null, 0);
        assertThat(listTestCases(skipDataset)).hasSize(1);

        UUID overrideDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> overrideResponse = importCsv(overrideDataset, csv, "OVERRIDE");
        assertThat(overrideResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(overrideResponse.getBody()).isNotNull();
        assertThat(overrideResponse.getBody().getOverriddenCount()).isIn(null, 0);
        assertThat(listTestCases(overrideDataset)).hasSize(1);
    }

    @Test
    @DisplayName("OVERRIDE import preserves perTurn on the persisted schema and reports it in autoDetectedSchema")
    void overrideImportPreservesPerTurnScope() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\nconv,0,hello\nconv,1,world";

        ResponseEntity<CsvImportPreviewDto> previewResponse = previewCsv(datasetId, csv, "OVERRIDE", null);
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(previewResponse.getBody()).isNotNull();
        FieldDefinitionDto previewPrompt = fieldByName(previewResponse.getBody().getAutoDetectedSchema(), "prompt");
        assertThat(previewPrompt.getPerTurn()).isTrue();

        ResponseEntity<CsvImportResultDto> importResponse = importCsv(datasetId, csv, "OVERRIDE", null);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        DatasetResponseDto dataset = getDataset(datasetId);
        FieldDefinitionDto persistedPrompt = fieldByName(dataset.getTestCaseSchema(), "prompt");
        assertThat(persistedPrompt.getPerTurn()).isTrue();
    }

    @Test
    @DisplayName("Export -> import(OVERRIDE) -> export -> import(OVERRIDE) is a stable round trip (issue #120)")
    void doubleRoundTripOverridePreservesTurns() {
        UUID sourceDataset = promptDataset();
        createMultiTurnCase(sourceDataset, "conv", List.of(Map.of("prompt", "first"), Map.of("prompt", "second")));
        String firstExport = exportCsv(sourceDataset);

        UUID targetDataset = promptDataset();
        assertDoubleRoundTripPreservesTurns(targetDataset, firstExport, "OVERRIDE", null);
    }

    @Test
    @DisplayName("Export -> import(APPEND, conflictStrategy=OVERRIDE) round trip is stable")
    void doubleRoundTripAppendOverrideStrategyPreservesTurns() {
        UUID sourceDataset = promptDataset();
        createMultiTurnCase(sourceDataset, "conv", List.of(Map.of("prompt", "first"), Map.of("prompt", "second")));
        String firstExport = exportCsv(sourceDataset);

        UUID targetDataset = promptDataset();
        assertDoubleRoundTripPreservesTurns(targetDataset, firstExport, "APPEND", "OVERRIDE");
    }

    @Test
    @DisplayName("Export -> import(MERGE, conflictStrategy=OVERRIDE) round trip is stable")
    void doubleRoundTripMergeOverrideStrategyPreservesTurns() {
        UUID sourceDataset = promptDataset();
        createMultiTurnCase(sourceDataset, "conv", List.of(Map.of("prompt", "first"), Map.of("prompt", "second")));
        String firstExport = exportCsv(sourceDataset);

        UUID targetDataset = promptDataset();
        assertDoubleRoundTripPreservesTurns(targetDataset, firstExport, "MERGE", "OVERRIDE");
    }

    @Test
    @DisplayName("Preview of a multi-turn CSV emits no within-CSV duplicate warning and assembles one sample "
            + "with multiTurnData, reporting totalRows = row count and totalTestCases = case count")
    void previewAssemblesMultiTurnCaseWithoutDuplicateWarning() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\nconv,0,hello\nconv,1,world";

        ResponseEntity<CsvImportPreviewDto> response = previewCsv(datasetId, csv, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalRows()).isEqualTo(2);
        assertThat(body.getTotalTestCases()).isEqualTo(1);
        assertThat(body.getWarnings()).noneMatch(w -> w.getMessage().contains("duplicate of earlier row"));
        assertThat(body.getSampleRows()).hasSize(1);
        TestCaseResponseDto sample = body.getSampleRows().get(0);
        assertThat(sample.getTestCaseName()).isEqualTo("conv");
        assertThat(sample.getMultiTurnData()).hasSize(2);
        assertThat(sample.getMultiTurnData().get(0).get("prompt")).isEqualTo("hello");
        assertThat(sample.getMultiTurnData().get(1).get("prompt")).isEqualTo("world");
    }

    @Test
    @DisplayName("Preview still flags two adjacent identical single-turn names as a duplicate, with "
            + "totalTestCases == totalRows, and import(SKIP) still collides on them (run != test case, D3)")
    void previewAndImportTreatAdjacentIdenticalSingleTurnNamesAsTwoCases() {
        UUID previewDataset = promptDataset();
        String csv = "testCaseName,prompt\ndup,a\ndup,b";

        ResponseEntity<CsvImportPreviewDto> previewResponse = previewCsv(previewDataset, csv, null, null);
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto previewBody = previewResponse.getBody();
        assertThat(previewBody).isNotNull();
        assertThat(previewBody.getTotalRows()).isEqualTo(2);
        assertThat(previewBody.getTotalTestCases()).isEqualTo(2);
        assertThat(previewBody.getWarnings()).anyMatch(w -> w.getMessage().contains("duplicate of earlier row"));

        UUID importDataset = promptDataset();
        ResponseEntity<CsvImportResultDto> importResponse = importCsv(importDataset, csv, "OVERRIDE", "SKIP");
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody()).isNotNull();
        assertThat(importResponse.getBody().getSkippedCount()).isEqualTo(1);
        assertThat(listTestCases(importDataset)).hasSize(1);
    }

    @Test
    @DisplayName("Preview reports a duplicate turnIndex within a multi-turn case as a conflict, matching import")
    void previewPredictsDuplicateTurnIndexConflict() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,a\ndup,0,b";

        ResponseEntity<CsvImportPreviewDto> response = previewCsv(datasetId, csv, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalTestCases()).isEqualTo(1);
        assertThat(body.getWarnings()).anyMatch(w -> w.getMessage().contains("Duplicate turnIndex"));
        assertThat(body.getSampleRows()).hasSize(1);
        assertThat(body.getSampleRows().get(0).isValid()).isFalse();
    }

    @Test
    @DisplayName("Preview reports a shared-column mismatch across turn rows as a conflict, matching import")
    void previewPredictsSharedColumnMismatchConflict() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt,category\nconv,0,a,catA\nconv,1,b,catB";

        ResponseEntity<CsvImportPreviewDto> response = previewCsv(datasetId, csv, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalTestCases()).isEqualTo(1);
        assertThat(body.getWarnings())
                .anyMatch(
                        w -> w.getMessage().contains("Shared") && w.getMessage().contains("must be identical"));
        assertThat(body.getSampleRows()).hasSize(1);
        assertThat(body.getSampleRows().get(0).isValid()).isFalse();
    }

    @Test
    @DisplayName("Preview against an empty dataset schema still reports the all-shared conflict when turn "
            + "rows differ on a column, invalidating the case")
    void previewPredictsSharedColumnMismatchAgainstEmptySchema() {
        UUID datasetId = newDatasetWithSchema(List.of());
        String csv = "testCaseName,turnIndex,prompt\nconv,0,a\nconv,1,b";

        ResponseEntity<CsvImportPreviewDto> response = previewCsv(datasetId, csv, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTotalTestCases()).isEqualTo(1);
        assertThat(body.getWarnings())
                .anyMatch(
                        w -> w.getMessage().contains("Shared") && w.getMessage().contains("must be identical"));
        assertThat(body.getSampleRows()).hasSize(1);
        assertThat(body.getSampleRows().get(0).isValid()).isFalse();
    }

    @Test
    @DisplayName("Preview reports a multi-turn name reappearing non-contiguously as both a non-contiguity "
            + "conflict and an ordinary within-CSV duplicate — the reappearance is a real collision at "
            + "persist time, matching import")
    void previewPredictsNonContiguousNameConflict() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\nconv,0,a\nother,0,x\nconv,1,b";

        ResponseEntity<CsvImportPreviewDto> response = previewCsv(datasetId, csv, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CsvImportPreviewDto body = response.getBody();
        assertThat(body).isNotNull();
        // Every row carries a turnIndex, so all three runs (conv / other / conv) are each their own
        // one-turn multi-turn case: totalTestCases == totalRows here.
        assertThat(body.getTotalTestCases()).isEqualTo(3);
        assertThat(body.getWarnings())
                .anyMatch(w -> w.getMessage().contains("non-contiguously") && w.getRowNumber() == 4);
        // The second "conv" run registers a repeat name occurrence too, so it also collides at persist
        // time under every conflict strategy — preview must surface that as the ordinary duplicate
        // warning alongside the non-contiguity one, not instead of it.
        assertThat(body.getWarnings())
                .anyMatch(w -> w.getMessage().contains("duplicate of earlier row") && w.getRowNumber() == 4);
    }

    // -------------------- Post-persist fixup pass (RC3, task 4) --------------------

    @Test
    @DisplayName("Post-persist fixup coerces per-turn values to the newly inferred type and re-validates "
            + "scope-aware — a required per-turn field is checked per turn, not against empty shared data")
    void fixupCoercesAndRevalidatesMultiTurnCase() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("score")
                .type(SchemaFieldType.INTEGER)
                .required(true)
                .perTurn(true)
                .build()));
        String csv = "testCaseName,turnIndex,score\nconv,0,42\nconv,1,hello";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.getFirst();
        assertThat(conv.getMultiTurnData()).hasSize(2);
        // 42 was stored natively as a number before the schema widened to STRING; the fixup pass coerces it.
        assertThat(conv.getMultiTurnData().get(0).get("score")).isEqualTo("42");
        assertThat(conv.getMultiTurnData().get(1).get("score")).isEqualTo("hello");
        // Valid: "score" is required and perTurn, so it is checked against each turn's own map — never
        // against the (empty) shared `data` — which is only true if the fixup re-validated via
        // validateMultiTurn rather than validateTestCase against the whole schema.
        assertThat(conv.isValid()).isTrue();
        assertThat(conv.getValidationWarnings()).isNullOrEmpty();

        DatasetResponseDto dataset = getDataset(datasetId);
        FieldDefinitionDto score = fieldByName(dataset.getTestCaseSchema(), "score");
        assertThat(score.getType()).isEqualTo(SchemaFieldType.STRING);
        assertThat(score.getPerTurn()).isTrue();
    }

    @Test
    @DisplayName("Post-persist fixup leaves a case whose stored turn array is unreadable untouched — turns "
            + "stay present and multiTurnData is never nulled to single-turn")
    void fixupSkipsUnreadableTurnArray() {
        UUID datasetId = newDatasetWithSchema(List.of());
        UUID corruptedId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "corrupted", "{}");
        // Valid JSON (jsonb requires it), but the wrong shape for a turn array (elements are numbers, not
        // objects) — deserializeTurnsStrict genuinely throws on this.
        metaTestDataHelper.forceRawMultiTurnData(corruptedId, "[1,2,3]");

        // APPEND (not OVERRIDE) so the pre-seeded corrupted row is not deleted before fixup runs; the empty
        // schema makes col1 auto-detected and widened (42 then hello), which is what drives fixup to run.
        String csv = "testCaseName,col1\nRow1,42\nRow2,hello";
        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, "APPEND", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        TestCase stored =
                testCaseRepository.findByIdAndDatasetId(corruptedId, datasetId).orElseThrow();
        assertThat(stored.getMultiTurnData()).isNotNull();
        assertThat(stored.getData()).isEqualTo("{}");
        assertThat(stored.isValid()).isTrue();
    }

    @Test
    @DisplayName("Post-persist fixup's re-serialized turn keeps every key except an explicit-null value the "
            + "shared NON_NULL ObjectMapper drops (pre-existing trade-off; must not silently widen)")
    void fixupPreservesPerTurnKeySetExceptExplicitNull() throws IOException {
        UUID datasetId = newDatasetWithSchema(List.of());
        UUID caseId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "conv", "{}");
        metaTestDataHelper.forceRawMultiTurnData(
                caseId,
                "[{\"score\":42,\"note\":null,\"label\":\"a\"}," + "{\"score\":\"5\",\"note\":\"b\",\"label\":\"c\"}]");

        // "score" is the column whose type widens (INTEGER then STRING) — it must match a key actually
        // present in the seeded turns above, or the fixup pass finds nothing to coerce and never rewrites
        // the row, which would make this test pass vacuously.
        String csv = "testCaseName,score\nRow1,42\nRow2,hello";
        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, "APPEND", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        TestCase stored =
                testCaseRepository.findByIdAndDatasetId(caseId, datasetId).orElseThrow();
        List<Map<String, Object>> turns = objectMapper.readValue(stored.getMultiTurnData(), new TypeReference<>() {});
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0)).containsKeys("score", "label").doesNotContainKey("note");
        assertThat(turns.get(0).get("score")).isEqualTo("42");
        assertThat(turns.get(0).get("label")).isEqualTo("a");
        assertThat(turns.get(1)).containsKeys("score", "note", "label");
    }

    // -------------------- Dataset revalidation Phase 1 — multi-turn (RC4, task 5.6/5.6b) --------------------

    @Test
    @DisplayName("Removing a per-turn field via a dataset schema PUT prunes it from every stored turn of a "
            + "normal multi-turn case (which stays valid), and leaves null/[]/[1,2,3]-shaped multi_turn_data "
            + "rows byte-identical rather than erroring the request or collapsing them to single-turn")
    void removingPerTurnFieldPrunesItFromEveryTurn() {
        UUID datasetId = newDatasetWithSchema(List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("extra")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .perTurn(true)
                        .build()));
        TestCaseResponseDto conv = createMultiTurnCase(
                datasetId, "conv", List.of(Map.of("prompt", "a", "extra", "x"), Map.of("prompt", "b", "extra", "y")));
        assertThat(conv.isValid()).isTrue();

        // Three rows whose multi_turn_data is NOT a JSONB array of objects — the shapes a naive
        // `IS NOT NULL` guard on the prune SQL gets wrong: the JSONB scalar `null` (satisfies
        // `IS NOT NULL` but jsonb_array_elements() on a scalar errors), an empty array (jsonb_agg over
        // zero rows returns SQL NULL unless COALESCEd, silently converting the case to single-turn),
        // and an array of non-object elements (subtracting keys from a scalar element errors). None of
        // the three carry the "extra" field, so pruning must be a complete no-op on all of them.
        UUID nullTurnsId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "null-turns", "{}");
        metaTestDataHelper.forceRawMultiTurnData(nullTurnsId, "null");
        UUID emptyArrayId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "empty-array-turns", "{}");
        metaTestDataHelper.forceRawMultiTurnData(emptyArrayId, "[]");
        UUID scalarArrayId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "scalar-array-turns", "{}");
        metaTestDataHelper.forceRawMultiTurnData(scalarArrayId, "[1,2,3]");

        String nullTurnsBefore = rawMultiTurnData(nullTurnsId, datasetId);
        String emptyArrayBefore = rawMultiTurnData(emptyArrayId, datasetId);
        String scalarArrayBefore = rawMultiTurnData(scalarArrayId, datasetId);

        // Drop "extra" from the schema — DatasetService.update prunes it from `data` today; task 5.6b
        // widens that prune to every element of multi_turn_data too, and the subsequent Phase 1 (task 5.6)
        // must not then flag the stale "extra" key as unknown on any turn. The request must succeed (202,
        // task COMPLETED) despite the three odd rows sharing the dataset with the normal case.
        RevalidationTaskDto task = updateSchemaAndAwaitRevalidation(
                datasetId,
                List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .build()));
        assertThat(task.getStatus()).isEqualTo(RevalidationStatus.COMPLETED);

        TestCase stored =
                testCaseRepository.findByIdAndDatasetId(conv.getId(), datasetId).orElseThrow();
        List<Map<String, Object>> turns = objectMapper.readValue(stored.getMultiTurnData(), new TypeReference<>() {});
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0)).containsKey("prompt").doesNotContainKey("extra");
        assertThat(turns.get(1)).containsKey("prompt").doesNotContainKey("extra");
        assertThat(stored.isValid()).isTrue();

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        TestCaseResponseDto convDto = cases.stream()
                .filter(tc -> "conv".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(convDto.isValid()).isTrue();
        assertThat(convDto.getValidationWarnings()).isNullOrEmpty();

        assertThat(rawMultiTurnData(nullTurnsId, datasetId)).isEqualTo(nullTurnsBefore);
        assertThat(rawMultiTurnData(emptyArrayId, datasetId)).isEqualTo(emptyArrayBefore);
        assertThat(rawMultiTurnData(scalarArrayId, datasetId)).isEqualTo(scalarArrayBefore);
    }

    @Test
    @DisplayName("Dataset revalidation Phase 1 leaves a case whose stored turn array is unreadable "
            + "untouched — data, turns, validity and updatedAt are all unchanged")
    void revalidationSkipsUnreadableTurnArray() {
        UUID datasetId = newDatasetWithSchema(List.of());
        UUID corruptedId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "corrupted", "{}");
        // Valid JSON (jsonb requires it), but the wrong shape for a turn array — deserializeTurnsStrict
        // genuinely throws on this (elements are numbers, not per-turn objects).
        metaTestDataHelper.forceRawMultiTurnData(corruptedId, "[1,2,3]");
        TestCase before =
                testCaseRepository.findByIdAndDatasetId(corruptedId, datasetId).orElseThrow();

        // A schema PUT (not a CSV import) is the trigger this test targets — task 5.7's D6/D7 guard
        // applies on every Phase 1 run, dataset-schema-PUT included, not only inside a CSV import request.
        updateSchemaAndAwaitRevalidation(
                datasetId,
                List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .build()));

        TestCase after =
                testCaseRepository.findByIdAndDatasetId(corruptedId, datasetId).orElseThrow();
        assertThat(after.getData()).isEqualTo(before.getData());
        assertThat(after.getMultiTurnData()).isEqualTo(before.getMultiTurnData());
        assertThat(after.isValid()).isEqualTo(before.isValid());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    // -------------------- Durable import conflicts and multi-turn revalidation (RC4, task 5.8-5.10)
    // --------------------

    @Test
    @DisplayName("A duplicate-turnIndex conflict (numeric column, exercising the fixup coercion path) leaves "
            + "the persisted case invalid with a SOURCE_CONFLICT warning, not valid with warnings erased")
    void persistedCaseStaysInvalidAfterDuplicateTurnIndexNumeric() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,1\ndup,0,2";

        // importMode is explicit rather than relying on the endpoint's OVERRIDE default: only
        // OVERRIDE (or an empty schema) re-infers every column's type via shouldAutoDetectSchema,
        // which is what makes the fixup coercion pass below actually rewrite this row.
        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, "OVERRIDE", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);

        TestCase persisted = soleTestCase(datasetId);
        assertThat(persisted.isValid()).isFalse();
        assertThat(parseWarnings(persisted))
                .anyMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT
                        && w.getMessage() != null
                        && w.getMessage().contains("Duplicate turnIndex"));
        // Proves the fixup pass actually rewrote this row (the dataset's "prompt" field was declared
        // STRING; "1"/"2" are all-numeric so the CSV infers INTEGER, and fixupMultiTurnCase coerces
        // each turn's "prompt" cell to that inferred type) rather than leaving both turns as the
        // original strings — which is the scenario where the SOURCE_CONFLICT warning is most at risk
        // of being erased by the recomputation this pass performs.
        List<Map<String, Object>> turns = parseTurns(persisted);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).get("prompt")).isEqualTo(1);
        assertThat(turns.get(1).get("prompt")).isEqualTo(2);
        DatasetResponseDto dataset = getDataset(datasetId);
        assertThat(fieldByName(dataset.getTestCaseSchema(), "prompt").getType()).isEqualTo(SchemaFieldType.INTEGER);
    }

    @Test
    @DisplayName("A duplicate-turnIndex conflict (all-string column, fixup finds nothing to coerce) still "
            + "leaves the persisted case invalid with a SOURCE_CONFLICT warning")
    void persistedCaseStaysInvalidAfterDuplicateTurnIndexString() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,a\ndup,0,b";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);

        TestCase persisted = soleTestCase(datasetId);
        assertThat(persisted.isValid()).isFalse();
        assertThat(parseWarnings(persisted))
                .anyMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT
                        && w.getMessage() != null
                        && w.getMessage().contains("Duplicate turnIndex"));
    }

    @Test
    @DisplayName("A shared-column mismatch across turn rows leaves the persisted case invalid with a "
            + "SOURCE_CONFLICT warning after import")
    void persistedCaseStaysInvalidAfterSharedColumnMismatch() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt,category\nconv,0,a,catA\nconv,1,b,catB";

        ResponseEntity<CsvImportResultDto> response = importCsv(datasetId, csv, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);

        TestCase persisted = soleTestCase(datasetId);
        assertThat(persisted.isValid()).isFalse();
        assertThat(parseWarnings(persisted))
                .anyMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT
                        && w.getMessage() != null
                        && w.getMessage().contains("Shared")
                        && w.getMessage().contains("must be identical"));
    }

    @Test
    @DisplayName("A SOURCE_CONFLICT warning from CSV import survives a later dataset schema PUT (revalidation "
            + "Phase 1), and is cleared by a direct PATCH of the case")
    void sourceConflictWarningSurvivesRevalidationAndIsClearedByPatch() {
        UUID datasetId = promptDataset();
        String csv = "testCaseName,turnIndex,prompt\ndup,0,a\ndup,0,b";
        ResponseEntity<CsvImportResultDto> importResponse = importCsv(datasetId, csv, null);
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        TestCase afterImport = soleTestCase(datasetId);
        assertThat(afterImport.isValid()).isFalse();
        assertThat(parseWarnings(afterImport)).anyMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT);

        // Trigger a dataset schema PUT that adds an unrelated optional field — Phase 1 recomputes the
        // case's verdict from stored data alone and must not erase the conflict it cannot re-derive.
        updateSchemaAndAwaitRevalidation(
                datasetId,
                List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .perTurn(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("note")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .perTurn(true)
                                .build()));

        TestCase afterRevalidation = soleTestCase(datasetId);
        assertThat(afterRevalidation.isValid()).isFalse();
        assertThat(parseWarnings(afterRevalidation))
                .anyMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT);

        // A direct PATCH supplies new, caller-authored content — that path does not call the durable-
        // warning merger, so the SOURCE_CONFLICT warning is legitimately cleared, unlike a recomputation.
        ResponseEntity<TestCaseResponseDto> patchResponse = patchTestCase(
                datasetId,
                afterRevalidation.getId(),
                Map.of("multiTurnData", List.of(Map.of("prompt", "x"), Map.of("prompt", "y"))));
        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patchResponse.getBody()).isNotNull();
        assertThat(patchResponse.getBody().isValid()).isTrue();
        assertThat(patchResponse.getBody().getValidationWarnings()).isEmpty();

        TestCase afterPatch = testCaseRepository
                .findByIdAndDatasetId(afterRevalidation.getId(), datasetId)
                .orElseThrow();
        assertThat(afterPatch.isValid()).isTrue();
        assertThat(parseWarnings(afterPatch)).noneMatch(w -> w.getCode() == ValidationWarningCode.SOURCE_CONFLICT);
    }

    @Test
    @DisplayName("A dataset schema PUT that narrows a per-turn field's type invalidates a case whose turn "
            + "value cannot be coerced, even though its shared data stays empty and clean")
    void perTurnTypeViolationInvalidatesCaseOnSchemaPut() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("score")
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(true)
                .build()));
        TestCaseResponseDto conv = createMultiTurnCase(datasetId, "conv", List.of(Map.of("score", "hello")));
        assertThat(conv.isValid()).isTrue();

        updateSchemaAndAwaitRevalidation(
                datasetId,
                List.of(FieldDefinitionDto.builder()
                        .name("score")
                        .type(SchemaFieldType.NUMBER)
                        .required(false)
                        .perTurn(true)
                        .build()));

        TestCase stored =
                testCaseRepository.findByIdAndDatasetId(conv.getId(), datasetId).orElseThrow();
        assertThat(stored.getData()).isEqualTo("{}");
        assertThat(stored.isValid()).isFalse();
        assertThat(parseWarnings(stored)).anyMatch(w -> Integer.valueOf(0).equals(w.getTurnIndex()));
        assertThat(parseTurns(stored).get(0).get("score")).isEqualTo("hello");
    }

    @Test
    @DisplayName("A dataset schema PUT widening a per-turn field to BOOLEAN coerces a coercible per-turn "
            + "value and persists it inside the turn map")
    void perTurnCoercibleValueCoercedAndPersistedOnSchemaPut() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(true)
                .build()));
        TestCaseResponseDto conv =
                createMultiTurnCase(datasetId, "conv", List.of(Map.of("flag", "true"), Map.of("flag", "false")));
        assertThat(conv.isValid()).isTrue();

        updateSchemaAndAwaitRevalidation(
                datasetId,
                List.of(FieldDefinitionDto.builder()
                        .name("flag")
                        .type(SchemaFieldType.BOOLEAN)
                        .required(false)
                        .perTurn(true)
                        .build()));

        TestCase stored =
                testCaseRepository.findByIdAndDatasetId(conv.getId(), datasetId).orElseThrow();
        List<Map<String, Object>> turns = parseTurns(stored);
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).get("flag")).isEqualTo(true);
        assertThat(turns.get(1).get("flag")).isEqualTo(false);
        assertThat(stored.isValid()).isTrue();
        assertThat(parseWarnings(stored)).isEmpty();
    }

    // -------------------- Helpers --------------------

    private TestCase soleTestCase(UUID datasetId) {
        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).hasSize(1);
        return testCaseRepository
                .findByIdAndDatasetId(cases.get(0).getId(), datasetId)
                .orElseThrow();
    }

    private List<ValidationWarningDto> parseWarnings(TestCase tc) {
        String json = tc.getValidationWarnings();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private List<Map<String, Object>> parseTurns(TestCase tc) {
        return objectMapper.readValue(tc.getMultiTurnData(), new TypeReference<>() {});
    }

    private ResponseEntity<TestCaseResponseDto> patchTestCase(UUID datasetId, UUID id, Map<String, Object> patchBody) {
        return restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/" + id + "?includeWarnings=true"),
                HttpMethod.PATCH,
                jsonEntity(patchBody),
                TestCaseResponseDto.class);
    }

    private String rawMultiTurnData(UUID testCaseId, UUID datasetId) {
        return testCaseRepository
                .findByIdAndDatasetId(testCaseId, datasetId)
                .orElseThrow()
                .getMultiTurnData();
    }

    /**
     * PUTs {@code newSchema} onto the dataset's {@code testCaseSchema} and awaits the resulting
     * revalidation task to a terminal status. Mirrors {@code RevalidationCoercionFunctionalTests}'
     * suite-rooted helper, but keyed directly on {@code datasetId} since this suite's fixtures are
     * dataset-only (no suite).
     */
    private RevalidationTaskDto updateSchemaAndAwaitRevalidation(UUID datasetId, List<FieldDefinitionDto> newSchema) {
        DatasetResponseDto dataset = getDataset(datasetId);
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name(dataset.getName())
                .description(dataset.getDescription())
                .testCaseSchema(newSchema)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(dataset.getVersion() != null ? "\"" + dataset.getVersion() + "\"" : "0");
        ResponseEntity<RevalidationTaskDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                RevalidationTaskDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        RevalidationTaskDto task = response.getBody();
        assertThat(task).isNotNull();
        RevalidationTaskDto completed = awaitRevalidationTerminal(datasetId, task.getTaskId(), 20);
        assertThat(completed.getStatus()).isEqualTo(RevalidationStatus.COMPLETED);
        return completed;
    }

    private RevalidationTaskDto awaitRevalidationTerminal(UUID datasetId, UUID taskId, int seconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<RevalidationTaskDto> r = restTemplate.getForEntity(
                    apiUrl("/datasets/" + datasetId + "/revalidation-tasks/" + taskId), RevalidationTaskDto.class);
            if (r.getStatusCode() == HttpStatus.OK && r.getBody() != null) {
                RevalidationStatus status = r.getBody().getStatus();
                if (status == RevalidationStatus.COMPLETED
                        || status == RevalidationStatus.FAILED
                        || status == RevalidationStatus.TIMED_OUT) {
                    return r.getBody();
                }
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
        throw new AssertionError("Revalidation did not complete in " + seconds + "s");
    }

    /**
     * Exports {@code firstExport} into {@code targetDataset}, exports it back out and asserts the CSV is
     * byte-identical to {@code firstExport}, imports that second CSV again, and asserts the case's turn
     * count, per-turn values, and the schema's {@code perTurn} scope all survive both imports unchanged —
     * the issue #120 repro (per design task 1.7/1.8; not whole-{@code FieldDefinitionDto} equality, since
     * {@code required} is deliberately still dropped by the CSV-derived schema builder).
     */
    private void assertDoubleRoundTripPreservesTurns(
            UUID targetDataset, String firstExport, String importMode, String conflictStrategy) {
        ResponseEntity<CsvImportResultDto> firstImport =
                importCsv(targetDataset, firstExport, importMode, conflictStrategy);
        assertThat(firstImport.getStatusCode()).isEqualTo(HttpStatus.OK);

        String secondExport = exportCsv(targetDataset);
        assertThat(secondExport).isEqualTo(firstExport);

        ResponseEntity<CsvImportResultDto> secondImport =
                importCsv(targetDataset, secondExport, importMode, conflictStrategy);
        assertThat(secondImport.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> cases = listTestCases(targetDataset);
        assertThat(cases).hasSize(1);
        TestCaseResponseDto conv = cases.get(0);
        assertThat(conv.getMultiTurnData()).hasSize(2);
        assertThat(conv.getMultiTurnData().stream().map(t -> t.get("prompt"))).containsExactly("first", "second");
        assertThat(conv.getData()).isNullOrEmpty();

        DatasetResponseDto dataset = getDataset(targetDataset);
        FieldDefinitionDto prompt = fieldByName(dataset.getTestCaseSchema(), "prompt");
        assertThat(prompt.getPerTurn()).isTrue();
    }

    private FieldDefinitionDto fieldByName(List<FieldDefinitionDto> schema, String name) {
        assertThat(schema).isNotNull();
        return schema.stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Field '" + name + "' not found in schema: " + schema));
    }

    private DatasetResponseDto getDataset(UUID datasetId) {
        ResponseEntity<DatasetResponseDto> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId), DatasetResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private String exportCsv(UUID datasetId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<CsvImportResultDto> importCsv(UUID datasetId, String csv, String conflictStrategy) {
        return importCsv(datasetId, csv, null, conflictStrategy);
    }

    private ResponseEntity<CsvImportResultDto> importCsv(
            UUID datasetId, String csv, String importMode, String conflictStrategy) {
        String url = "/datasets/" + datasetId + "/test-cases/import?delimiter=,"
                + (importMode != null ? "&importMode=" + importMode : "")
                + (conflictStrategy != null ? "&conflictStrategy=" + conflictStrategy : "");
        return restTemplate.postForEntity(apiUrl(url), multipartFileEntity(csv), CsvImportResultDto.class);
    }

    private ResponseEntity<CsvImportPreviewDto> previewCsv(
            UUID datasetId, String csv, String importMode, String conflictStrategy) {
        String url = "/datasets/" + datasetId + "/test-cases/import/preview?delimiter=,"
                + (importMode != null ? "&importMode=" + importMode : "")
                + (conflictStrategy != null ? "&conflictStrategy=" + conflictStrategy : "");
        return restTemplate.postForEntity(apiUrl(url), multipartFileEntity(csv), CsvImportPreviewDto.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartFileEntity(String csvContent) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "multi-turn.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private List<TestCaseResponseDto> listTestCases(UUID datasetId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        return list.getBody().getContent();
    }

    private List<CSVRecord> parseCsv(String csvContent) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get();
        try (CSVParser parser = CSVParser.builder()
                .setReader(new StringReader(csvContent))
                .setFormat(format)
                .get()) {
            return parser.getRecords();
        } catch (UncheckedIOException e) {
            throw new IOException(e.getCause());
        }
    }
}
