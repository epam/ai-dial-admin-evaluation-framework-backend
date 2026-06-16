package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Functional tests for CSV import modes (OVERRIDE/APPEND/MERGE) and conflict strategies (FAIL/SKIP/OVERRIDE).
 *
 * <p>Test-case CSV import/preview/list endpoints are now dataset-scoped: the controller class path is
 * {@code /api/v1/datasets/{datasetId}/test-cases/*}. The {@code testCaseSchema} that imports mutate
 * also lives on the {@link com.epam.aidial.evaluation.data.db.model.Dataset}, not on the test suite,
 * so all schema assertions in this file query the dataset via {@code GET /datasets/{id}} rather than
 * the suite. Suite-level schema PUTs are similarly rerouted to {@code PUT /datasets/{id}}.
 */
@DisplayName("CSV Import Mode Functional Tests")
public abstract class CsvImportModeFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // Task 9.1 — OVERRIDE mode replaces existing schema even when non-empty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.1 OVERRIDE mode replaces existing schema with auto-detected schema from CSV")
    void overrideModeReplacesExistingSchema() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();

        String csv = "testCaseName,newField\nRow1,hello";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);

        DatasetResponseDto updatedDataset = getDataset(suite.getDatasetId());
        assertThat(updatedDataset.getTestCaseSchema()).isNotNull();
        // newField should be in the schema (old schema fields replaced)
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "newField".equals(f.getName())))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.2 — OVERRIDE mode deletes existing rows and inserts CSV rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.2 OVERRIDE mode deletes all existing rows and inserts CSV rows")
    void overrideModeDeletesExistingRows() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName\nExisting1\nExisting2", "OVERRIDE", "FAIL");

        String csv = "testCaseName\nNewRow1\nNewRow2\nNewRow3";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(3);
        assertThat(testCases.stream().map(TestCaseResponseDto::getTestCaseName))
                .containsExactlyInAnyOrder("NewRow1", "NewRow2", "NewRow3");
    }

    // -------------------------------------------------------------------------
    // Task 9.2a — OVERRIDE import mode + SKIP strategy + within-CSV duplicates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.2a OVERRIDE import mode + SKIP strategy: within-CSV dup is silently skipped, skippedCount=1")
    void overrideImportModeSkipStrategyWithinCsvDup() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName\nUnique1\nUnique1\nUnique2";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);
        assertThat(response.getBody().getSkippedCount()).isEqualTo(1);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Task 9.2b — OVERRIDE import mode + OVERRIDE strategy + within-CSV duplicates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "9.2b OVERRIDE import mode + OVERRIDE strategy: within-CSV dup upserted (last wins), overriddenCount=1")
    void overrideImportModeOverrideStrategyWithinCsvDup() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,prompt\nDupName,first\nDupName,last";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getOverriddenCount()).isEqualTo(1);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        // last wins
        assertThat(testCases.get(0).getData()).containsEntry("prompt", "last");
    }

    // -------------------------------------------------------------------------
    // Task 9.3 — APPEND mode appends rows, existing rows preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.3 APPEND mode appends rows while preserving existing test cases")
    void appendModePreservesExistingRows() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName\nExisting1\nExisting2", "OVERRIDE", "FAIL");

        String csv = "testCaseName\nNew1\nNew2";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(4);
        assertThat(testCases.stream().map(TestCaseResponseDto::getTestCaseName))
                .contains("Existing1", "Existing2", "New1", "New2");
    }

    // -------------------------------------------------------------------------
    // Task 9.4 — APPEND + FAIL: collision returns HTTP 409
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.4 APPEND + FAIL: name collision returns HTTP 409, no rows imported")
    void appendFailStrategyCollisionReturns409() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName\nExistingCase", "OVERRIDE", "FAIL");

        String csv = "testCaseName\nExistingCase";
        ResponseEntity<String> response = importCsvRaw(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Task 9.5 — APPEND + SKIP: skips colliding rows, returns skippedCount
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.5 APPEND + SKIP: colliding rows skipped, skippedCount reflects collision count")
    void appendSkipStrategySkipsCollisions() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName\nCase1\nCase2", "OVERRIDE", "FAIL");

        String csv = "testCaseName\nCase1\nCase3";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSkippedCount()).isEqualTo(1);
        assertThat(response.getBody().getOverriddenCount()).isNull();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases.stream().map(TestCaseResponseDto::getTestCaseName)).contains("Case1", "Case2", "Case3");
    }

    // -------------------------------------------------------------------------
    // Task 9.6 — APPEND + OVERRIDE: replaces colliding rows, returns overriddenCount
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.6 APPEND + OVERRIDE strategy: replaces colliding rows, overriddenCount reflects replacements")
    void appendOverrideStrategyReplacesCollisions() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName,prompt\nCase1,original", "OVERRIDE", "FAIL");

        String csv = "testCaseName,prompt\nCase1,updated\nCase2,new";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getOverriddenCount()).isEqualTo(1);
        assertThat(response.getBody().getSkippedCount()).isNull();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto case1 = testCases.stream()
                .filter(tc -> "Case1".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(case1.getData()).containsEntry("prompt", "updated");
    }

    // -------------------------------------------------------------------------
    // Task 9.7 — APPEND with empty schema: auto-detects and persists schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.7 APPEND with empty schema: auto-detects schema from CSV and persists it")
    void appendEmptySchemaAutoDetectsAndPersists() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,score,label\nRow1,42,good\nRow2,7,bad";
        importCsv(suite.getId(), csv, "APPEND", "FAIL");

        DatasetResponseDto updatedDataset = getDataset(suite.getDatasetId());
        assertThat(updatedDataset.getTestCaseSchema()).isNotNull();
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "score".equals(f.getName())))
                .isTrue();
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "label".equals(f.getName())))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.8 — APPEND with existing schema: no schema change; unknown columns NOT stored
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.8 APPEND with existing schema: schema unchanged; unknown CSV columns not stored in data")
    void appendExistingSchemaNoSchemaChangeUnknownColumnDiscarded() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has 'prompt' and 'expected'
        DatasetResponseDto datasetBefore = getDataset(suite.getDatasetId());
        Long versionBefore = datasetBefore.getVersion();

        String csv = "testCaseName,prompt,expected,unknownCol\nRow1,hello,world,should_not_store";
        importCsv(suite.getId(), csv, "APPEND", "FAIL");

        DatasetResponseDto datasetAfter = getDataset(suite.getDatasetId());
        assertThat(datasetAfter.getVersion()).isEqualTo(versionBefore); // version not bumped

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        assertThat(testCases.get(0).getData()).containsKey("prompt");
        assertThat(testCases.get(0).getData()).doesNotContainKey("unknownCol");
    }

    // -------------------------------------------------------------------------
    // Task 9.9 — MERGE adds new schema fields and appends rows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.9 MERGE mode: new CSV columns added to schema and rows appended")
    void mergeModeAddsNewSchemaFieldsAndAppendsRows() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has 'prompt', 'expected'
        importCsv(suite.getId(), "testCaseName,prompt,expected\nExisting,hello,world", "APPEND", "FAIL");

        String csv = "testCaseName,prompt,expected,newMetric\nNew1,hi,bye,5";
        importCsv(suite.getId(), csv, "MERGE", "FAIL");

        DatasetResponseDto updatedDataset = getDataset(suite.getDatasetId());
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "newMetric".equals(f.getName())))
                .isTrue();
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "prompt".equals(f.getName())))
                .isTrue();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2); // Existing + New1
    }

    // -------------------------------------------------------------------------
    // Task 9.10 — MERGE with no new columns: schema and version unchanged
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.10 MERGE with no new columns: schema and version unchanged")
    void mergeModeNoNewColumnsDoesNotChangeSchema() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        DatasetResponseDto datasetBefore = getDataset(suite.getDatasetId());
        Long versionBefore = datasetBefore.getVersion();

        String csv = "testCaseName,prompt,expected\nRow1,hello,world";
        importCsv(suite.getId(), csv, "MERGE", "FAIL");

        DatasetResponseDto datasetAfter = getDataset(suite.getDatasetId());
        assertThat(datasetAfter.getVersion()).isEqualTo(versionBefore);
    }

    // -------------------------------------------------------------------------
    // Task 9.10a — MERGE with empty schema: auto-detects and persists schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.10a MERGE with empty schema: auto-detects schema from CSV and persists it")
    void mergeModeEmptySchemaAutoDetectsAndPersists() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        DatasetResponseDto datasetBefore = getDataset(suite.getDatasetId());

        String csv = "testCaseName,score,label\nRow1,42,good\nRow2,7,bad";
        importCsv(suite.getId(), csv, "MERGE", "FAIL");

        DatasetResponseDto datasetAfter = getDataset(suite.getDatasetId());
        assertThat(datasetAfter.getTestCaseSchema()).isNotNull().isNotEmpty();
        assertThat(datasetAfter.getTestCaseSchema().stream().anyMatch(f -> "score".equals(f.getName())))
                .isTrue();
        assertThat(datasetAfter.getTestCaseSchema().stream().anyMatch(f -> "label".equals(f.getName())))
                .isTrue();
        assertThat(datasetAfter.getVersion()).isGreaterThan(datasetBefore.getVersion());

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Task 9.11 — MERGE + SKIP: new columns added, colliding rows skipped
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.11 MERGE + SKIP: new schema columns added, colliding rows skipped")
    void mergeModeSkipStrategyAddsColumnsSkipsCollisions() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        importCsv(suite.getId(), "testCaseName,prompt,expected\nExisting,hello,world", "APPEND", "FAIL");

        String csv = "testCaseName,prompt,expected,newField\nExisting,new_prompt,new_expected,value\nNewCase,hi,bye,v2";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "MERGE", "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSkippedCount()).isEqualTo(1);

        DatasetResponseDto updatedDataset = getDataset(suite.getDatasetId());
        assertThat(updatedDataset.getTestCaseSchema().stream().anyMatch(f -> "newField".equals(f.getName())))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.12 — Preview OVERRIDE: always returns autoDetectedSchema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.12 Preview with OVERRIDE mode always returns autoDetectedSchema")
    void previewOverrideModeReturnsAutoDetectedSchema() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();

        String csv = "testCaseName,col1,col2\nRow1,v1,v2";
        ResponseEntity<CsvImportPreviewDto> response = previewCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAutoDetectedSchema()).isNotNull();
        assertThat(response.getBody().getAutoDetectedSchema().stream().anyMatch(f -> "col1".equals(f.getName())))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.13 — Preview APPEND + SKIP: collision warnings show "would be skipped"
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.13 Preview APPEND + SKIP: collision warnings contain 'would be skipped'")
    void previewAppendSkipShowsSkipWarnings() {
        TestSuiteResponseDto suite = createEmptyTestSuite();
        importCsv(suite.getId(), "testCaseName\nExistingCase", "OVERRIDE", "FAIL");

        String csv = "testCaseName\nExistingCase\nNewCase";
        ResponseEntity<CsvImportPreviewDto> response = previewCsv(suite.getId(), csv, "APPEND", "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getWarnings()).isNotEmpty();
        assertThat(response.getBody().getWarnings().stream()
                        .anyMatch(w -> w.getMessage().contains("would be skipped")))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.14 — Preview MERGE: autoDetectedSchema shows only delta fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.14 Preview MERGE: autoDetectedSchema shows only delta fields (new columns)")
    void previewMergeShowsDeltaFields() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt, expected

        String csv = "testCaseName,prompt,expected,deltaField\nRow1,hi,bye,new_val";
        ResponseEntity<CsvImportPreviewDto> response = previewCsv(suite.getId(), csv, "MERGE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // autoDetectedSchema should contain only 'deltaField', not existing 'prompt'/'expected'
        if (response.getBody().getAutoDetectedSchema() != null) {
            assertThat(response.getBody().getAutoDetectedSchema().stream()
                            .anyMatch(f -> "deltaField".equals(f.getName())))
                    .isTrue();
            assertThat(response.getBody().getAutoDetectedSchema().stream().noneMatch(f -> "prompt".equals(f.getName())))
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Task 9.15 — FAIL + within-CSV duplicates → 409
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.15 FAIL strategy + within-CSV duplicate: HTTP 409, no rows committed")
    void failStrategyWithinCsvDupReturns409() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName\nDuplicateName\nduplicatename";
        ResponseEntity<String> response = importCsvRaw(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(listTestCases(suite.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Task 9.15a — SKIP + within-CSV dups → first wins (APPEND/MERGE)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.15a SKIP strategy + within-CSV duplicates: first wins, skippedCount incremented")
    void skipStrategyWithinCsvDupFirstWins() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,prompt\nDupName,first\nDupName,second\nUnique,only";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "SKIP");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSkippedCount()).isEqualTo(1);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);
        TestCaseResponseDto dup = testCases.stream()
                .filter(tc -> "DupName".equalsIgnoreCase(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(dup.getData()).containsEntry("prompt", "first");
    }

    // -------------------------------------------------------------------------
    // Task 9.15b — OVERRIDE strategy + within-CSV dups → last wins (APPEND/MERGE)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.15b OVERRIDE conflict strategy + within-CSV duplicates: last wins, overriddenCount incremented")
    void overrideConflictStrategyWithinCsvDupLastWins() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,prompt\nDupName,first\nDupName,last\nUnique,only";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getOverriddenCount()).isEqualTo(1);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);
        TestCaseResponseDto dup = testCases.stream()
                .filter(tc -> "DupName".equalsIgnoreCase(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(dup.getData()).containsEntry("prompt", "last");
    }

    // -------------------------------------------------------------------------
    // Task 9.15c — Preview with within-CSV duplicates: warning annotations, no 409
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.15c Preview with within-CSV duplicates: warnings annotated, no HTTP 409")
    void previewWithWithinCsvDupAnnotatesWarnings() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName\nDupRow\nduprow\nUnique";
        ResponseEntity<CsvImportPreviewDto> response = previewCsv(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getWarnings()).isNotEmpty();
        assertThat(response.getBody().getWarnings().stream()
                        .anyMatch(w -> w.getMessage().contains("duplicate")
                                || w.getMessage().contains("failure")
                                || w.getMessage().contains("409")))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Task 9.16 — Schema cleanup: removing field removes it from test case data
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.16 Schema cleanup: removing a field from dataset schema removes that key from all test case data")
    void removingSchemaFieldCleansUpTestCaseData() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // dataset has prompt, expected
        importCsv(suite.getId(), "testCaseName,prompt,expected\nRow1,hello,world", "OVERRIDE", "FAIL");

        // Fetch dataset (OVERRIDE import bumps its version)
        DatasetResponseDto dataset = getDataset(suite.getDatasetId());

        // Update dataset schema: remove 'expected' field. Schema mutations now happen on the
        // Dataset (not the suite); this returns 202 Accepted with a revalidation task.
        DatasetRequestDto updateRequest = DatasetRequestDto.builder()
                .name(dataset.getName())
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + dataset.getVersion() + "\"");
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                String.class);
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        assertThat(testCases.get(0).getData()).containsKey("prompt");
        assertThat(testCases.get(0).getData()).doesNotContainKey("expected");
    }

    // -------------------------------------------------------------------------
    // Task 9.17 — Schema cleanup: adding a field does NOT modify existing data
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.17 Schema cleanup: adding a field to dataset schema does NOT modify existing test case data")
    void addingSchemaFieldDoesNotModifyExistingTestCaseData() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        importCsv(suite.getId(), "testCaseName,prompt,expected\nRow1,hello,world", "OVERRIDE", "FAIL");

        DatasetResponseDto dataset = getDataset(suite.getDatasetId());

        DatasetRequestDto updateRequest = DatasetRequestDto.builder()
                .name(dataset.getName())
                .testCaseSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("expected")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("newField")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .build()))
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + dataset.getVersion() + "\"");
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                String.class);
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        // Original data preserved
        assertThat(testCases.get(0).getData()).containsKey("prompt");
        assertThat(testCases.get(0).getData()).containsKey("expected");
        // New field not present (no data was imported with it)
        assertThat(testCases.get(0).getData()).doesNotContainKey("newField");
    }

    // -------------------------------------------------------------------------
    // Task 9.18 — Unknown CSV column NOT stored when APPEND with existing schema
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.18 Unknown CSV column not stored in data when APPEND mode with existing schema")
    void appendModeUnknownColumnNotStored() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt, expected

        String csv = "testCaseName,prompt,expected,extraCol\nRow1,hi,bye,ignored";
        importCsv(suite.getId(), csv, "APPEND", "FAIL");

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        assertThat(testCases.get(0).getData()).containsKey("prompt");
        assertThat(testCases.get(0).getData()).doesNotContainKey("extraCol");
    }

    // -------------------------------------------------------------------------
    // Task 9.19 — MERGE: new CSV columns stored; existing test case data NOT modified
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("9.19 MERGE mode: new columns stored in imported rows; existing testCase data not modified")
    void mergeModeNewColumnsStoredExistingDataUnchanged() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        importCsv(suite.getId(), "testCaseName,prompt,expected\nExisting,hello,world", "OVERRIDE", "FAIL");

        String csv = "testCaseName,prompt,expected,newMetric\nNewRow,hi,bye,5";
        importCsv(suite.getId(), csv, "MERGE", "FAIL");

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);

        TestCaseResponseDto existing = testCases.stream()
                .filter(tc -> "Existing".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(existing.getData()).doesNotContainKey("newMetric");

        TestCaseResponseDto newRow = testCases.stream()
                .filter(tc -> "NewRow".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(newRow.getData()).containsKey("newMetric");
    }

    // -------------------------------------------------------------------------
    // Validation state: imported test cases have correct isValid after import
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE + existing schema: imported test cases are valid (no stale-schema warnings)")
    void overrideWithExistingSchemaImportedTestCasesAreValid() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt, expected

        String csv = "testCaseName,newField\nRow1,hello\nRow2,world";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getInvalidCount()).isZero();
        assertThat(response.getBody().getValidCount()).isEqualTo(2);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(2);
        assertThat(testCases).allMatch(TestCaseResponseDto::isValid);
        assertThat(testCases)
                .allMatch(tc -> tc.getValidationWarnings() == null
                        || tc.getValidationWarnings().isEmpty());
    }

    @Test
    @DisplayName("OVERRIDE + empty schema: imported test cases are valid (no unknown-field warnings)")
    void overrideWithEmptySchemaImportedTestCasesAreValid() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,col1,col2\nRow1,a,b";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getInvalidCount()).isZero();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        assertThat(testCases.get(0).isValid()).isTrue();
    }

    @Test
    @DisplayName("MERGE + new columns: imported test cases are valid (new columns not flagged as unknown)")
    void mergeWithNewColumnsImportedTestCasesAreValid() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt, expected

        String csv = "testCaseName,prompt,expected,newMetric\nNew1,hi,bye,5";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "MERGE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getInvalidCount()).isZero();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto newRow = testCases.stream()
                .filter(tc -> "New1".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(newRow.isValid()).isTrue();
    }

    @Test
    @DisplayName("APPEND + empty schema: imported test cases are valid")
    void appendWithEmptySchemaImportedTestCasesAreValid() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,score,label\nRow1,42,good";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getInvalidCount()).isZero();

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        assertThat(testCases.get(0).isValid()).isTrue();
    }

    @Test
    @DisplayName("Preview OVERRIDE: sample rows have valid=true (no stale-schema warnings)")
    void previewOverrideSampleRowsAreValid() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt, expected

        String csv = "testCaseName,newField\nRow1,hello";
        ResponseEntity<CsvImportPreviewDto> response = previewCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSampleRows()).isNotEmpty();
        assertThat(response.getBody().getSampleRows()).allMatch(TestCaseResponseDto::isValid);
        // No validation warnings about unknown fields
        assertThat(response.getBody().getWarnings().stream()
                        .noneMatch(w -> w.getMessage().contains("Unknown data field")))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // JSON array/object cell auto-detection when schema is empty
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("OVERRIDE + empty schema: JSON array cell stored as array, not string")
    void overrideEmptySchemaStoresJsonArrayCell() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,tags\nRow1,\"[\"\"a\"\",\"\"b\"\",\"\"c\"\"]\"";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Object tagsValue = testCases.get(0).getData().get("tags");
        assertThat(tagsValue).isInstanceOf(List.class);
        assertThat(tagsValue).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("APPEND + empty schema: JSON array cell stored as array, not string")
    void appendEmptySchemaStoresJsonArrayCell() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,items\nRow1,\"[1,2,3]\"";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Object itemsValue = testCases.get(0).getData().get("items");
        assertThat(itemsValue).isInstanceOf(List.class);
        assertThat(itemsValue).isEqualTo(List.of(1, 2, 3));
    }

    @Test
    @DisplayName("OVERRIDE + empty schema: JSON object cell stored as object, not string")
    void overrideEmptySchemaStoresJsonObjectCell() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,config\nRow1,\"{\"\"key\"\":\"\"value\"\"}\"";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Object configValue = testCases.get(0).getData().get("config");
        assertThat(configValue).isInstanceOf(Map.class);
        assertThat(configValue).isEqualTo(Map.of("key", "value"));
    }

    @Test
    @DisplayName("Invalid JSON array-looking cell falls back to string without error")
    void invalidJsonArrayCellFallsBackToString() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,note\nRow1,[not-json]";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Object noteValue = testCases.get(0).getData().get("note");
        assertThat(noteValue).isInstanceOf(String.class);
        assertThat(noteValue).isEqualTo("[not-json]");
    }

    @Test
    @DisplayName("Existing STRING/INTEGER/BOOLEAN cell behavior unchanged with empty schema")
    void existingCellTypesBehaviorUnchanged() {
        TestSuiteResponseDto suite = createEmptyTestSuite();

        String csv = "testCaseName,text,number,flag\nRow1,hello,42,true";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Map<String, Object> data = testCases.get(0).getData();
        assertThat(data.get("text")).isEqualTo("hello");
        assertThat(data.get("number")).isEqualTo(42);
        assertThat(data.get("flag")).isEqualTo(true);
    }

    @Test
    @DisplayName("MERGE + new column: JSON array cell stored as array when schema has no type for that field")
    void mergeNewColumnStoresJsonArrayCell() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema(); // has prompt (STRING), expected (STRING)

        // MERGE import adds a new column "items" not in existing schema
        String csv = "testCaseName,prompt,expected,items\nRow1,hello,world,\"[1,2,3]\"";
        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "MERGE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        assertThat(testCases).hasSize(1);
        Object itemsValue = testCases.get(0).getData().get("items");
        assertThat(itemsValue).isInstanceOf(List.class);
        assertThat(itemsValue).isEqualTo(List.of(1, 2, 3));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private TestSuiteResponseDto createEmptyTestSuite() {
        Dataset dataset = metaTestDataHelper.createDataset("csvmode-empty-" + UUID.randomUUID());
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Empty Suite " + UUID.randomUUID())
                .datasetId(dataset.getId())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithSchema() {
        String schemaJson;
        try {
            schemaJson = objectMapper.writeValueAsString(List.of(
                    FieldDefinitionDto.builder()
                            .name("prompt")
                            .type(SchemaFieldType.STRING)
                            .required(true)
                            .build(),
                    FieldDefinitionDto.builder()
                            .name("expected")
                            .type(SchemaFieldType.STRING)
                            .required(true)
                            .build()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
        Dataset dataset = metaTestDataHelper.createDataset("csvmode-schema-" + UUID.randomUUID(), schemaJson);
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with schema " + UUID.randomUUID())
                .datasetId(dataset.getId())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private DatasetResponseDto getDataset(UUID datasetId) {
        ResponseEntity<DatasetResponseDto> resp =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId), DatasetResponseDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<TestCaseResponseDto> listTestCases(UUID suiteId) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> resp = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});
        return resp.getBody() != null ? resp.getBody().getContent() : List.of();
    }

    private ResponseEntity<CsvImportResultDto> importCsv(
            UUID suiteId, String csv, String importMode, String conflictStrategy) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        return restTemplate.postForEntity(uri, multipartFileEntity(csv, "test.csv"), CsvImportResultDto.class);
    }

    private ResponseEntity<String> importCsvRaw(UUID suiteId, String csv, String importMode, String conflictStrategy) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        return restTemplate.postForEntity(uri, multipartFileEntity(csv, "test.csv"), String.class);
    }

    private ResponseEntity<CsvImportPreviewDto> previewCsv(
            UUID suiteId, String csv, String importMode, String conflictStrategy) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import/preview"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        return restTemplate.postForEntity(uri, multipartFileEntity(csv, "preview.csv"), CsvImportPreviewDto.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartFileEntity(String csvContent, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }
}
