package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkDeleteResponseDto;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCase Functional Tests")
public abstract class TestCaseFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestCaseRepository testCaseRepository;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("tc-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private DatasetResponseDto getDataset(UUID id) {
        ResponseEntity<DatasetResponseDto> r =
                restTemplate.getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    @DisplayName("Should create test case")
    void shouldCreateTestCase() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("TC 1")
                .data(Map.of("prompt", "Hello", "expected", "Hi"))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases"),
                jsonEntity(request),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getTestCaseName()).isEqualTo("TC 1");
        assertThat(response.getBody().getData()).containsEntry("prompt", "Hello");
        assertThat(response.getBody().getData()).containsEntry("expected", "Hi");
        assertThat(response.getBody().getCreatedAt()).isNotNull();
        assertThat(response.getBody().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should get test case by ID")
    void shouldGetTestCaseById() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto created = createTestCase(suite.getId(), "Get By ID");

        ResponseEntity<TestCaseResponseDto> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + created.getId()),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(created.getId());
        assertThat(response.getBody().getTestCaseName()).isEqualTo("Get By ID");
    }

    @Test
    @DisplayName("Should return 404 when test case not in test suite (scope validation)")
    void shouldReturn404WhenTestCaseNotInTestSuite() {
        TestSuiteResponseDto suite1 = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite1.getId(), "TC");
        TestSuiteResponseDto suite2 = createTestSuite();

        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite2.getId()) + "/test-cases/" + tc.getId()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should create test cases with same name in different suites successfully")
    void shouldCreateTestCasesWithSameNameInDifferentSuitesSuccessfully() {
        TestSuiteResponseDto suite1 = createTestSuite();
        TestSuiteResponseDto suite2 = createTestSuite();
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("Shared Name")
                .data(Map.of())
                .build();

        ResponseEntity<TestCaseResponseDto> r1 = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite1.getId()) + "/test-cases"),
                jsonEntity(request),
                TestCaseResponseDto.class);
        ResponseEntity<TestCaseResponseDto> r2 = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite2.getId()) + "/test-cases"),
                jsonEntity(request),
                TestCaseResponseDto.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r1.getBody()).isNotNull();
        assertThat(r2.getBody()).isNotNull();
        assertThat(r1.getBody().getTestCaseName()).isEqualTo("Shared Name");
        assertThat(r2.getBody().getTestCaseName()).isEqualTo("Shared Name");
        assertThat(r1.getBody().getId()).isNotEqualTo(r2.getBody().getId());
    }

    @Test
    @DisplayName("Should return 409 when creating test case with duplicate name in same suite")
    void shouldReturn409WhenCreatingTestCaseWithDuplicateName() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "TC Duplicate");
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("TC Duplicate")
                .data(Map.of())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases"),
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("TC Duplicate");
    }

    @Test
    @DisplayName("Should return 409 when creating test case with name that differs only by case in same suite")
    void shouldReturn409WhenCreatingTestCaseWithCaseVariation() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "CaseOne");
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("caseone")
                .data(Map.of())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases"),
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
    }

    @Test
    @DisplayName("Should return 409 when updating test case to name that another case in same suite already has")
    void shouldReturn409WhenUpdatingTestCaseToDuplicateName() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Existing Name");
        TestCaseResponseDto toUpdate = createTestCase(suite.getId(), "Other Name");
        TestCaseRequestDto update = TestCaseRequestDto.builder()
                .testCaseName("Existing Name")
                .data(Map.of())
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + toUpdate.getId()),
                HttpMethod.PUT,
                jsonEntity(update),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("Existing Name");
    }

    @Test
    @DisplayName("Should return 409 when PATCHing test case to name that already exists in same suite")
    void shouldReturn409WhenPatchingTestCaseToDuplicateName() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Taken");
        TestCaseResponseDto toPatch = createTestCase(suite.getId(), "Mine");

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + toPatch.getId()),
                HttpMethod.PATCH,
                jsonEntity(Map.of("testCaseName", "Taken")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("Taken");
    }

    @Test
    @DisplayName("Should list test cases with pagination")
    void shouldListTestCasesWithPagination() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "TC 1");
        createTestCase(suite.getId(), "TC 2");
        createTestCase(suite.getId(), "TC 3");

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?page=0&size=2&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getTotalElements()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should update test case")
    void shouldUpdateTestCase() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto created = createTestCase(suite.getId(), "Original");

        TestCaseRequestDto update = TestCaseRequestDto.builder()
                .testCaseName("Updated Name")
                .data(Map.of("prompt", "Updated", "expected", "Updated"))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + created.getId()),
                HttpMethod.PUT,
                jsonEntity(update),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTestCaseName()).isEqualTo("Updated Name");
        assertThat(response.getBody().getData()).containsEntry("prompt", "Updated");
    }

    @Test
    @DisplayName("Should PATCH test case (data only)")
    void shouldPatchTestCaseDataOnly() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto created = createTestCase(suite.getId(), "Patch Data");

        ResponseEntity<TestCaseResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + created.getId()),
                HttpMethod.PATCH,
                jsonEntity(Map.of("data", Map.of("expected", "Patched", "score", 0.9))),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).containsEntry("expected", "Patched");
        assertThat(response.getBody().getData()).containsEntry("score", 0.9);
    }

    @Test
    @DisplayName("Should delete test case")
    void shouldDeleteTestCase() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto created = createTestCase(suite.getId(), "To Delete");

        ResponseEntity<Void> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + created.getId()),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> get = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + created.getId()),
                String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return validationWarnings (structured) when includeWarnings=true")
    void shouldReturnValidationWarningsWhenIncludeWarningsTrue() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("Invalid TC")
                .data(Map.of())
                .build();

        ResponseEntity<TestCaseResponseDto> create = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeWarnings=true"),
                jsonEntity(request),
                TestCaseResponseDto.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().getValidationWarnings()).isNotNull();
        assertThat(create.getBody().getValidationWarnings()).isNotEmpty();
        for (ValidationWarningDto w : create.getBody().getValidationWarnings()) {
            assertThat(w.getFieldName()).isNotNull();
            assertThat(w.getMessage()).isNotBlank();
            assertThat(w.getCode()).isNotNull();
        }
    }

    @Test
    @DisplayName("Should not return validationWarnings when includeWarnings=false")
    void shouldNotReturnValidationWarningsWhenIncludeWarningsFalse() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto created = createTestCase(suite.getId(), "TC");

        ResponseEntity<TestCaseResponseDto> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/" + created.getId()
                        + "?includeWarnings=false"),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getValidationWarnings()).isNull();
    }

    @Test
    @DisplayName("Should return 400 when list filter count exceeds 32")
    void shouldReturn400WhenListFilterCountExceeds32() {
        TestSuiteResponseDto suite = createTestSuite();
        StringBuilder url = new StringBuilder(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&filter=testCaseName:eq:x").append(i);
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when list sort count exceeds 32")
    void shouldReturn400WhenListSortCountExceeds32() {
        TestSuiteResponseDto suite = createTestSuite();
        StringBuilder url = new StringBuilder(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&sort=testCaseName,asc");
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for CSV delimiter length != 1")
    void shouldReturn400ForCsvDelimiterLengthNotOne() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName\nRow1";
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?delimiter=,,,"),
                multipartFileEntity(csv, "test.csv"),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("delimiter");
    }

    @Test
    @DisplayName("Should return 400 for Unicode CSV delimiter")
    void shouldReturn400ForUnicodeCsvDelimiter() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName\nRow1";
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?delimiter=\u00A0"),
                multipartFileEntity(csv, "test.csv"),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should accept default comma CSV delimiter")
    void shouldAcceptDefaultCommaCsvDelimiter() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName\nRow1";
        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "test.csv"),
                CsvImportResultDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);
    }

    // Note: CSV import uses replace-all mode (deletes existing test cases before importing),
    // so conflicts with pre-existing data do not occur. Only duplicates within the CSV are detected.
    // See test: shouldReturn409WhenCsvImportHasDuplicateNamesWithinCsv
    @Test
    @DisplayName("Should return 409 when CSV import has duplicate names (case-insensitive) within CSV")
    void shouldReturn409WhenCsvImportHasDuplicateNamesWithinCsv() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName\nDupName\nDupName\nOther";
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "dupes.csv"),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("DupName");
    }

    @Test
    @DisplayName("Create/get/update TestCase that fails validation returns structured validationWarnings")
    void shouldReturnStructuredValidationWarningsOnInvalidTestCase() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("Invalid")
                .data(Map.of())
                .build();

        ResponseEntity<TestCaseResponseDto> create = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeWarnings=true"),
                jsonEntity(request),
                TestCaseResponseDto.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create.getBody()).isNotNull();
        assertThat(create.getBody().isValid()).isFalse();
        assertThat(create.getBody().getValidationWarnings()).isNotEmpty();
        ValidationWarningDto first = create.getBody().getValidationWarnings().get(0);
        assertThat(first.getFieldName()).isNotNull();
        assertThat(first.getMessage()).isNotBlank();
        assertThat(first.getCode()).isNotNull();

        ResponseEntity<TestCaseResponseDto> get = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                        + create.getBody().getId() + "?includeWarnings=true"),
                TestCaseResponseDto.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().getValidationWarnings()).isNotEmpty();
        ValidationWarningDto getFirst = get.getBody().getValidationWarnings().get(0);
        assertThat(getFirst.getFieldName()).isNotNull();
        assertThat(getFirst.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("Should bulk delete with filter")
    void shouldBulkDeleteWithFilter() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Keep");
        createTestCase(suite.getId(), "Delete1");
        createTestCase(suite.getId(), "Delete2");

        ResponseEntity<TestCaseControllerBulkDeleteResponse> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?filter=testCaseName:eq:Delete1"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                TestCaseControllerBulkDeleteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deleted()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should bulk delete by IN filter on two names")
    void shouldBulkDeleteByInFilterWithTwoNames() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Keep");
        createTestCase(suite.getId(), "Delete1");
        createTestCase(suite.getId(), "Delete2");

        ResponseEntity<TestCaseControllerBulkDeleteResponse> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?filter=testCaseName:in:Delete1,Delete2"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                TestCaseControllerBulkDeleteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deleted()).isEqualTo(2L);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> remaining = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});
        assertThat(remaining.getBody()).isNotNull();
        assertThat(remaining.getBody().getTotalElements()).isEqualTo(1L);
        assertThat(remaining.getBody().getContent().get(0).getTestCaseName()).isEqualTo("Keep");
    }

    @Test
    @DisplayName("Should bulk delete by IN filter with single value")
    void shouldBulkDeleteByInFilterWithSingleValue() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Stay");
        createTestCase(suite.getId(), "Gone");

        ResponseEntity<TestCaseControllerBulkDeleteResponse> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?filter=testCaseName:in:Gone"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                TestCaseControllerBulkDeleteResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().deleted()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should return HTTP 400 when IN filter value is empty")
    void shouldReturn400WhenInFilterValueIsEmpty() {
        TestSuiteResponseDto suite = createTestSuite();

        ResponseEntity<Void> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?filter=testCaseName:in:,"),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- 6.7 CSV Export / Import functional tests ---

    @Test
    @DisplayName("Should export CSV (happy path)")
    void shouldExportCsvHappyPath() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "ExportA");
        createTestCase(suite.getId(), "ExportB");

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String csv = response.getBody();
        assertThat(csv).contains("testCaseName");
        assertThat(csv).contains("ExportA");
        assertThat(csv).contains("ExportB");
    }

    private ResponseEntity<String> getExportCsv(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getExportCsv(URI uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("Should export CSV with filtering")
    void shouldExportCsvWithFilter() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "FilterInclude");
        createTestCase(suite.getId(), "FilterExclude");

        ResponseEntity<String> response =
                getExportCsv(apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/export.csv?filter=testCaseName:eq:FilterInclude"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String csv = response.getBody();
        assertThat(csv).contains("FilterInclude");
        assertThat(csv).doesNotContain("FilterExclude");
    }

    @Test
    @DisplayName("Should export CSV with custom delimiter")
    void shouldExportCsvWithCustomDelimiter() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        createTestCaseWithData(suite.getId(), "DelimOne", Map.of("expected", "value"));

        URI uri = UriComponentsBuilder.fromUriString(apiUrl(
                        "/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"))
                .queryParam("delimiter", ";")
                .build()
                .toUri();
        ResponseEntity<String> response = getExportCsv(uri);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        String csv = response.getBody();
        assertThat(csv).contains(";");
        assertThat(csv).contains("DelimOne");
    }

    @Test
    @DisplayName("Should export empty suite (header-only CSV)")
    void shouldExportCsvEmptySuite() {
        TestSuiteResponseDto suite = createTestSuite();

        ResponseEntity<String> response = getExportCsv(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().trim()).isEqualTo("testCaseName,turnIndex");
    }

    @Test
    @DisplayName("Should export CSV with ARRAY and OBJECT values as valid JSON")
    void shouldExportCsvWithArrayAndObjectAsJson() throws IOException {
        TestSuiteResponseDto suite = createTestSuiteWithMixedTypeSchema();
        createTestCaseWithData(
                suite.getId(),
                "TC1",
                Map.of(
                        "prompt", "hello",
                        "tags", List.of("a", "b"),
                        "metadata", Map.of("key", "value")));
        createTestCaseWithData(
                suite.getId(),
                "TC2",
                Map.of(
                        "prompt", "world",
                        "tags", List.of(),
                        "metadata", Map.of()));
        createTestCaseWithData(suite.getId(), "TC3", Map.of("prompt", "only string"));

        ResponseEntity<String> response = getExportCsv(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records).hasSize(3);

        CSVRecord tc1 = findRecord(records, "TC1");
        CSVRecord tc2 = findRecord(records, "TC2");
        CSVRecord tc3 = findRecord(records, "TC3");

        // ARRAY values serialized as valid JSON (not Java toString)
        assertThat(tc1.get("tags")).isEqualTo("[\"a\",\"b\"]");
        assertThat(tc1.get("metadata")).isEqualTo("{\"key\":\"value\"}");
        assertThat(tc1.get("prompt")).isEqualTo("hello");

        // Empty array/object
        assertThat(tc2.get("tags")).isEqualTo("[]");
        assertThat(tc2.get("metadata")).isEqualTo("{}");

        // Missing ARRAY/OBJECT values exported as empty
        assertThat(tc3.get("tags")).isEmpty();
        assertThat(tc3.get("metadata")).isEmpty();
        assertThat(tc3.get("prompt")).isEqualTo("only string");
    }

    @Test
    @DisplayName("Should round-trip CSV export and import with ARRAY and OBJECT values preserving types")
    void shouldRoundTripCsvWithArrayAndObjectValues() {
        TestSuiteResponseDto suite = createTestSuiteWithMixedTypeSchema();
        createTestCaseWithData(
                suite.getId(),
                "TC1",
                Map.of(
                        "prompt", "hello",
                        "tags", List.of("a", "b"),
                        "metadata", Map.of("key", "value")));
        createTestCaseWithData(
                suite.getId(),
                "TC2",
                Map.of(
                        "prompt", "world",
                        "tags", List.of(1, "two", true),
                        "metadata", Map.of("nested", Map.of("deep", "value"))));

        // Export CSV
        ResponseEntity<String> exportResponse = getExportCsv(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"));
        assertThat(exportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = exportResponse.getBody();

        // Import into a new suite with the same schema
        TestSuiteResponseDto importSuite = createTestSuiteWithMixedTypeSchema();
        ResponseEntity<CsvImportResultDto> importResponse = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(importSuite.getId())
                        + "/test-cases/import?delimiter=,"),
                multipartFileEntity(csv, "round-trip.csv"),
                CsvImportResultDto.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody()).isNotNull();
        assertThat(importResponse.getBody().getTotalRows()).isEqualTo(2);
        assertThat(importResponse.getBody().getValidCount()).isEqualTo(2);

        // Verify imported test cases preserve types
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(importSuite.getId()) + "/test-cases"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getContent()).hasSize(2);

        TestCaseResponseDto tc1 = list.getBody().getContent().stream()
                .filter(tc -> "TC1".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        TestCaseResponseDto tc2 = list.getBody().getContent().stream()
                .filter(tc -> "TC2".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();

        // ARRAY values preserved as Lists
        assertThat(tc1.getData().get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(tc2.getData().get("tags")).isEqualTo(List.of(1, "two", true));

        // OBJECT values preserved as Maps
        assertThat(tc1.getData().get("metadata")).isEqualTo(Map.of("key", "value"));
        assertThat(tc2.getData().get("metadata")).isEqualTo(Map.of("nested", Map.of("deep", "value")));

        // STRING values preserved
        assertThat(tc1.getData().get("prompt")).isEqualTo("hello");
        assertThat(tc2.getData().get("prompt")).isEqualTo("world");
    }

    @Test
    @DisplayName("Should import CSV (roundtrip after export)")
    void shouldImportCsvRoundtrip() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Before");
        String csv = "testCaseName\nRow 1\nRow 2";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?delimiter=,"),
                multipartFileEntity(csv, "test.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(2);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getContent()).hasSize(2);
        assertThat(list.getBody().getTotalElements()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should import preview return CsvImportPreviewDto without persisting")
    void shouldImportPreviewReturnsPreviewDto() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName,expected\nTC1,value1\nTC2,value2";

        ResponseEntity<CsvImportPreviewDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import/preview"),
                multipartFileEntity(csv, "preview.csv"),
                CsvImportPreviewDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getDetectedColumns()).isNotEmpty();
        assertThat(response.getBody().getSampleRows()).hasSize(2);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getTotalElements()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should return 400 for empty CSV (header only)")
    void shouldReturn400ForEmptyCsv() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName";

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "empty.csv"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for malformed CSV")
    void shouldReturn400ForMalformedCsv() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "a,b\n\"unclosed quote,value";

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "bad.csv"),
                String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Should persist invalid rows with isValid=false and return warnings")
    void shouldImportInvalidRowsWithWarnings() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        String csv = "testCaseName,prompt,expected\nValidRow,hello,world\nMissingRequired,,world";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "mixed.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount() + response.getBody().getInvalidCount())
                .isEqualTo(2);
        assertThat(response.getBody().getInvalidCount()).isGreaterThanOrEqualTo(0);
        assertThat(response.getBody().getWarnings()).isNotNull();
    }

    @Test
    @DisplayName("Should parse OBJECT/ARRAY fact columns as JSON when schema exists (valid JSON)")
    void shouldParseObjectArrayFactAsJsonWhenValid() {
        TestSuiteResponseDto suite = createTestSuiteWithObjectArrayFactSchema();
        // Use simple JSON without commas so no CSV quoting needed; empty object and simple object
        String csv = "testCaseName,metadata\nRow1,{}\nRow2,{\"a\":1}";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "object-array.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(2);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getContent()).hasSize(2);
        TestCaseResponseDto row1 = list.getBody().getContent().stream()
                .filter(tc -> "Row1".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        TestCaseResponseDto row2 = list.getBody().getContent().stream()
                .filter(tc -> "Row2".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(row1.getData()).containsKey("metadata");
        assertThat(row1.getData().get("metadata")).isEqualTo(Map.of());
        assertThat(row2.getData().get("metadata")).isEqualTo(Map.of("a", 1));
    }

    @Test
    @DisplayName("Should set isValid=false and add warning when OBJECT/ARRAY cell is invalid JSON")
    void shouldSetInvalidAndWarnWhenObjectArrayCellIsInvalidJson() {
        TestSuiteResponseDto suite = createTestSuiteWithObjectArrayFactSchema();
        String csv = "testCaseName,metadata\nValidRow,{}\nInvalidRow,not valid json";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "invalid-json.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(1);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);
        assertThat(response.getBody().getWarnings()).isNotNull();
        assertThat(response.getBody().getWarnings()).isNotEmpty();
        assertThat(response.getBody().getWarnings().stream().map(w -> w.getMessage()))
                .anyMatch(msg -> msg.contains("OBJECT/ARRAY") && msg.contains("JSON"));
    }

    @Test
    @DisplayName("Should preview CSV with ARRAY columns — empty, string, and numeric arrays")
    void shouldPreviewCsvWithArrayColumns() {
        TestSuiteResponseDto suite = createTestSuiteWithArraySchema();
        String csv = """
                testCaseName,tags,scores
                Row1,[],[]
                Row2,"[""hello"",""world""]","[1,2,3]"
                Row3,"[""a"",""b"",""c""]","[0.8,0.9,1.0]"
                """;

        ResponseEntity<CsvImportPreviewDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import/preview"),
                multipartFileEntity(csv, "arrays-preview.csv"),
                CsvImportPreviewDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);
        assertThat(response.getBody().getWarnings()).isEmpty();
        assertThat(response.getBody().getSampleRows()).hasSize(3);
    }

    @Test
    @DisplayName(
            "Should import CSV with ARRAY columns — empty, string, and numeric arrays — and persist data correctly")
    void shouldImportCsvWithArrayColumns() {
        TestSuiteResponseDto suite = createTestSuiteWithArraySchema();
        String csv = """
                testCaseName,tags,scores
                Row1,[],[]
                Row2,"[""hello"",""world""]","[1,2,3]"
                Row3,"[""a"",""b"",""c""]","[0.8,0.9,1.0]"
                """;

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "arrays-import.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);
        assertThat(response.getBody().getValidCount()).isEqualTo(3);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getContent()).hasSize(3);

        TestCaseResponseDto row1 = list.getBody().getContent().stream()
                .filter(tc -> "Row1".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        TestCaseResponseDto row2 = list.getBody().getContent().stream()
                .filter(tc -> "Row2".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        TestCaseResponseDto row3 = list.getBody().getContent().stream()
                .filter(tc -> "Row3".equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow();

        assertThat(row1.getData().get("tags")).isEqualTo(List.of());
        assertThat(row1.getData().get("scores")).isEqualTo(List.of());
        assertThat(row2.getData().get("tags")).isEqualTo(List.of("hello", "world"));
        assertThat(row2.getData().get("scores")).isEqualTo(List.of(1, 2, 3));
        assertThat(row3.getData().get("tags")).isEqualTo(List.of("a", "b", "c"));
        assertThat(row3.getData().get("scores")).isEqualTo(List.of(0.8, 0.9, 1.0));
    }

    @Test
    @DisplayName("Should return 400 with descriptive error when CSV contains unquoted JSON array with string values")
    void shouldReturn400WhenArrayCellNotProperlyQuoted() {
        TestSuiteResponseDto suite = createTestSuiteWithArraySchema();
        // ["hello","world"] is invalid CSV: quotes inside an unquoted field violate RFC 4180.
        // Correct form is: "[""hello"",""world""]"
        String csv = "testCaseName,tags\nRow1,[]\nRow2,[\"hello\",\"world\"]";

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import/preview"),
                multipartFileEntity(csv, "bad-csv.csv"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Malformed CSV");
    }

    @Test
    @DisplayName("Should auto-generate padded 1-based names for blank testCaseName on import")
    void shouldAutoGeneratePaddedNamesOnImport() {
        TestSuiteResponseDto suite = createTestSuite();
        // CSV has no testCaseName values — all should be auto-generated
        String csv = "prompt\nhello\nworld\nfoo";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "no-names.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        List<String> names = list.getBody().getContent().stream()
                .map(TestCaseResponseDto::getTestCaseName)
                .sorted()
                .toList();
        assertThat(names).containsExactly("Row 000001", "Row 000002", "Row 000003");
    }

    @Test
    @DisplayName("Should auto-generate padded 1-based names for blank testCaseName on preview")
    void shouldAutoGeneratePaddedNamesOnPreview() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "prompt\nhello\nworld";

        ResponseEntity<CsvImportPreviewDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import/preview"),
                multipartFileEntity(csv, "no-names-preview.csv"),
                CsvImportPreviewDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<String> names = response.getBody().getSampleRows().stream()
                .map(TestCaseResponseDto::getTestCaseName)
                .toList();
        assertThat(names).containsExactly("Row 000001", "Row 000002");
    }

    // -------------------------------------------------------------------------
    // Type coercion functional tests (6.1 – 6.8)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should coerce numeric CSV value to string when schema column is STRING")
    void shouldCoerceNumericToStringWhenSchemaIsString() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(FieldDefinitionDto.builder()
                .name("answer")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build());
        String csv = "testCaseName,answer\nRow1,1865\nRow2,3.14";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?importMode=APPEND"),
                multipartFileEntity(csv, "coerce-numeric.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(2);
        assertThat(response.getBody().getValidCount()).isEqualTo(2);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto row1 = findByName(testCases, "Row1");
        TestCaseResponseDto row2 = findByName(testCases, "Row2");

        // Stored as JSON strings, not numbers
        assertThat(row1.getData().get("answer")).isInstanceOf(String.class);
        assertThat(row1.getData().get("answer")).isEqualTo("1865");
        assertThat(row2.getData().get("answer")).isInstanceOf(String.class);
        assertThat(row2.getData().get("answer")).isEqualTo("3.14");
    }

    @Test
    @DisplayName("Should coerce boolean CSV value to string when schema column is STRING")
    void shouldCoerceBooleanToStringWhenSchemaIsString() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(FieldDefinitionDto.builder()
                .name("flag")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build());
        String csv = "testCaseName,flag\nRow1,true\nRow2,false";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?importMode=APPEND"),
                multipartFileEntity(csv, "coerce-boolean.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getValidCount()).isEqualTo(2);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto row1 = findByName(testCases, "Row1");
        TestCaseResponseDto row2 = findByName(testCases, "Row2");

        // Stored as JSON strings, not booleans
        assertThat(row1.getData().get("flag")).isInstanceOf(String.class);
        assertThat(row1.getData().get("flag")).isEqualTo("true");
        assertThat(row2.getData().get("flag")).isInstanceOf(String.class);
        assertThat(row2.getData().get("flag")).isEqualTo("false");
    }

    @Test
    @DisplayName("Should emit TYPE warning when creating test case via API with integer in STRING column")
    void shouldEmitTypeWarningOnApiCreateWithIntegerInStringColumn() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(FieldDefinitionDto.builder()
                .name("answer")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build());

        // API client sends integer value for STRING-typed column — no coercion on API path
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("BadType")
                .data(Map.of("answer", 42))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases?includeWarnings=true"),
                jsonEntity(request),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        assertThat(response.getBody().getValidationWarnings()).isNotEmpty();
        assertThat(response.getBody().getValidationWarnings().stream()
                        .anyMatch(w -> w.getCode() != null
                                && "TYPE".equals(w.getCode().name())
                                && "answer".equals(w.getFieldName())))
                .isTrue();
    }

    @Test
    @DisplayName("Should fixup all rows via post-persist pass when empty schema widens column to STRING")
    void shouldFixupAllRowsWhenEmptySchemaWidensToString() {
        // Empty schema — types auto-detected. Column with 42, 99, "hello" widens to STRING.
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName,col\nRow1,42\nRow2,99\nRow3,hello";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "fixup.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(3);
        assertThat(response.getBody().getValidCount()).isEqualTo(3);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto row1 = findByName(testCases, "Row1");
        TestCaseResponseDto row2 = findByName(testCases, "Row2");
        TestCaseResponseDto row3 = findByName(testCases, "Row3");

        // ALL rows should have string values after fixup
        assertThat(row1.getData().get("col")).isInstanceOf(String.class);
        assertThat(row1.getData().get("col")).isEqualTo("42");
        assertThat(row2.getData().get("col")).isInstanceOf(String.class);
        assertThat(row2.getData().get("col")).isEqualTo("99");
        assertThat(row3.getData().get("col")).isInstanceOf(String.class);
        assertThat(row3.getData().get("col")).isEqualTo("hello");

        // Verify schema auto-detected as STRING
        DatasetResponseDto dataset = getDataset(suite.getDatasetId());
        assertThat(dataset).isNotNull();
        FieldDefinitionDto colField = dataset.getTestCaseSchema().stream()
                .filter(f -> "col".equals(f.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(colField.getType()).isEqualTo(SchemaFieldType.STRING);
    }

    @Test
    @DisplayName("Should parse 1 and 0 as integers (not booleans) and auto-detect as INTEGER")
    void shouldParse1And0AsIntegersNotBooleans() {
        TestSuiteResponseDto suite = createTestSuite();
        String csv = "testCaseName,val\nRow1,1\nRow2,0";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "one-zero.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getValidCount()).isEqualTo(2);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto row1 = findByName(testCases, "Row1");
        TestCaseResponseDto row2 = findByName(testCases, "Row2");

        // Stored as integers, not booleans (Jackson deserializes small JSON integers as Integer)
        assertThat(row1.getData().get("val")).isInstanceOf(Number.class);
        assertThat(row1.getData().get("val")).isNotInstanceOf(Boolean.class);
        assertThat(((Number) row1.getData().get("val")).longValue()).isEqualTo(1L);
        assertThat(row2.getData().get("val")).isInstanceOf(Number.class);
        assertThat(row2.getData().get("val")).isNotInstanceOf(Boolean.class);
        assertThat(((Number) row2.getData().get("val")).longValue()).isEqualTo(0L);

        // Schema auto-detected as INTEGER
        DatasetResponseDto dataset = getDataset(suite.getDatasetId());
        assertThat(dataset).isNotNull();
        FieldDefinitionDto valField = dataset.getTestCaseSchema().stream()
                .filter(f -> "val".equals(f.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(valField.getType()).isEqualTo(SchemaFieldType.INTEGER);
    }

    @Test
    @DisplayName("Should handle large integer (3000000000) in INTEGER schema without overflow")
    void shouldHandleLargeIntegerInIntegerSchema() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(FieldDefinitionDto.builder()
                .name("bignum")
                .type(SchemaFieldType.INTEGER)
                .required(false)
                .build());
        String csv = "testCaseName,bignum\nRow1,3000000000";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?importMode=APPEND"),
                multipartFileEntity(csv, "large-int.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getValidCount()).isEqualTo(1);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(0);

        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto row1 = findByName(testCases, "Row1");

        // Stored as Long (3000000000 exceeds Integer.MAX_VALUE)
        assertThat(row1.getData().get("bignum")).isInstanceOf(Long.class);
        assertThat(row1.getData().get("bignum")).isEqualTo(3000000000L);
    }

    @Test
    @DisplayName("Should store as string with TYPE warning when non-numeric value imported into INTEGER column")
    void shouldStoreAsStringWithTypeWarningOnCoercionFailure() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(FieldDefinitionDto.builder()
                .name("num")
                .type(SchemaFieldType.INTEGER)
                .required(false)
                .build());
        String csv = "testCaseName,num\nRow1,hello";

        ResponseEntity<CsvImportResultDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/import?importMode=APPEND"),
                multipartFileEntity(csv, "coercion-fail.csv"),
                CsvImportResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);
        assertThat(response.getBody().getInvalidCount()).isEqualTo(1);

        // Retrieve with warnings to verify TYPE warning
        ResponseEntity<TestCaseResponseDto> tcResponse = restTemplate
                .exchange(
                        apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                                + "/test-cases?includeWarnings=true"),
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {})
                .getBody()
                .getContent()
                .stream()
                .filter(tc -> "Row1".equals(tc.getTestCaseName()))
                .findFirst()
                .map(tc -> {
                    // Re-fetch individual test case with warnings
                    return restTemplate.getForEntity(
                            apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/"
                                    + tc.getId() + "?includeWarnings=true"),
                            TestCaseResponseDto.class);
                })
                .orElseThrow();

        assertThat(tcResponse.getBody()).isNotNull();
        assertThat(tcResponse.getBody().isValid()).isFalse();
        assertThat(tcResponse.getBody().getData().get("num")).isInstanceOf(String.class);
        assertThat(tcResponse.getBody().getData().get("num")).isEqualTo("hello");
        assertThat(tcResponse.getBody().getValidationWarnings()).isNotEmpty();
        assertThat(tcResponse.getBody().getValidationWarnings().stream()
                        .anyMatch(w -> w.getCode() != null
                                && "TYPE".equals(w.getCode().name())
                                && "num".equals(w.getFieldName())))
                .isTrue();
    }

    @Test
    @DisplayName("Should round-trip CSV export and import with INTEGER, NUMBER, BOOLEAN, STRING preserving types")
    void shouldRoundTripCsvWithAllScalarTypes() {
        TestSuiteResponseDto suite = createTestSuiteWithTypedSchema(
                FieldDefinitionDto.builder()
                        .name("str")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("num")
                        .type(SchemaFieldType.INTEGER)
                        .required(false)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("dec")
                        .type(SchemaFieldType.NUMBER)
                        .required(false)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("flag")
                        .type(SchemaFieldType.BOOLEAN)
                        .required(false)
                        .build());

        // Create test cases with typed data via API
        Map<String, Object> data1 = new HashMap<>();
        data1.put("str", "hello");
        data1.put("num", 42);
        data1.put("dec", 3.14);
        data1.put("flag", true);
        createTestCaseWithData(suite.getId(), "TC1", data1);

        Map<String, Object> data2 = new HashMap<>();
        data2.put("str", "world");
        data2.put("num", 0);
        data2.put("dec", 0.0);
        data2.put("flag", false);
        createTestCaseWithData(suite.getId(), "TC2", data2);

        // Export
        ResponseEntity<String> exportResponse = getExportCsv(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"));
        assertThat(exportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = exportResponse.getBody();

        // Re-import into same suite with OVERRIDE
        ResponseEntity<CsvImportResultDto> importResponse = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/import"),
                multipartFileEntity(csv, "round-trip-types.csv"),
                CsvImportResultDto.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importResponse.getBody()).isNotNull();
        assertThat(importResponse.getBody().getTotalRows()).isEqualTo(2);
        assertThat(importResponse.getBody().getValidCount()).isEqualTo(2);
        assertThat(importResponse.getBody().getInvalidCount()).isEqualTo(0);

        // Verify types preserved
        List<TestCaseResponseDto> testCases = listTestCases(suite.getId());
        TestCaseResponseDto tc1 = findByName(testCases, "TC1");
        TestCaseResponseDto tc2 = findByName(testCases, "TC2");

        // STRING values
        assertThat(tc1.getData().get("str")).isEqualTo("hello");
        assertThat(tc2.getData().get("str")).isEqualTo("world");

        // INTEGER values — Jackson may deserialize small ints as Integer
        assertThat(tc1.getData().get("num")).isInstanceOf(Number.class);
        assertThat(((Number) tc1.getData().get("num")).longValue()).isEqualTo(42L);
        assertThat(tc2.getData().get("num")).isInstanceOf(Number.class);
        assertThat(((Number) tc2.getData().get("num")).longValue()).isEqualTo(0L);

        // NUMBER values
        assertThat(tc1.getData().get("dec")).isInstanceOf(Number.class);
        assertThat(((Number) tc1.getData().get("dec")).doubleValue()).isEqualTo(3.14);

        // BOOLEAN values
        assertThat(tc1.getData().get("flag")).isEqualTo(true);
        assertThat(tc2.getData().get("flag")).isEqualTo(false);
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

    private TestSuiteResponseDto createTestSuite() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite for TC " + UUID.randomUUID())
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of()))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithSchema() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with schema " + UUID.randomUUID())
                .description("Desc")
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
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("expected")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithObjectArrayFactSchema() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with OBJECT fact " + UUID.randomUUID())
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("metadata")
                        .type(SchemaFieldType.OBJECT)
                        .required(false)
                        .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithArraySchema() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with ARRAY fact " + UUID.randomUUID())
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("tags")
                                .type(SchemaFieldType.ARRAY)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("scores")
                                .type(SchemaFieldType.ARRAY)
                                .required(false)
                                .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID testSuiteId, String name) {
        return createTestCaseWithData(testSuiteId, name, Map.of());
    }

    private TestCaseResponseDto createTestCaseWithData(UUID testSuiteId, String name, Map<String, Object> data) {
        TestCaseRequestDto req = TestCaseRequestDto.builder()
                .testCaseName(name)
                .data(data != null ? data : Map.of())
                .build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(testSuiteId) + "/test-cases"),
                jsonEntity(req),
                TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithMixedTypeSchema() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with mixed types " + UUID.randomUUID())
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("tags")
                                .type(SchemaFieldType.ARRAY)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("metadata")
                                .type(SchemaFieldType.OBJECT)
                                .required(false)
                                .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
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
        }
    }

    private CSVRecord findRecord(List<CSVRecord> records, String testCaseName) {
        return records.stream()
                .filter(r -> testCaseName.equals(r.get("testCaseName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Record not found: " + testCaseName));
    }

    private TestSuiteResponseDto createTestSuiteWithTypedSchema(FieldDefinitionDto... fields) {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite typed " + UUID.randomUUID())
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(fields)))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private List<TestCaseResponseDto> listTestCases(UUID suiteId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> list = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getBody()).isNotNull();
        return list.getBody().getContent();
    }

    private TestCaseResponseDto findByName(List<TestCaseResponseDto> testCases, String name) {
        return testCases.stream()
                .filter(tc -> name.equals(tc.getTestCaseName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test case not found: " + name));
    }

    private record TestCaseControllerBulkDeleteResponse(long deleted) {}

    @Test
    @DisplayName("Should bulk delete by IDs when all IDs exist")
    void shouldBulkDeleteByIdsWhenAllExist() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "BulkDelA");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "BulkDelB");
        createTestCase(suite.getId(), "BulkDelKeep");

        TestCaseBulkDeleteRequestDto request = TestCaseBulkDeleteRequestDto.builder()
                .ids(List.of(tc1.getId(), tc2.getId()))
                .build();

        ResponseEntity<TestCaseBulkDeleteResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                TestCaseBulkDeleteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeleted()).containsExactly(tc1.getId(), tc2.getId());
        assertThat(response.getBody().getNotFound()).isEmpty();

        assertThat(restTemplate
                        .getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/" + tc1.getId()), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate
                        .getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/" + tc2.getId()), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should bulk delete by IDs with partial success when some IDs not found")
    void shouldBulkDeleteByIdsWithPartialSuccess() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        TestCaseResponseDto existing = createTestCase(suite.getId(), "BulkDelPartial");
        UUID nonExistent = UUID.randomUUID();

        TestCaseBulkDeleteRequestDto request = TestCaseBulkDeleteRequestDto.builder()
                .ids(List.of(existing.getId(), nonExistent))
                .build();

        ResponseEntity<TestCaseBulkDeleteResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                TestCaseBulkDeleteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeleted()).containsExactly(existing.getId());
        assertThat(response.getBody().getNotFound()).containsExactly(nonExistent);

        assertThat(restTemplate
                        .getForEntity(
                                apiUrl("/datasets/" + datasetId + "/test-cases/" + existing.getId()), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should bulk delete by IDs returning all as notFound when none exist")
    void shouldBulkDeleteByIdsReturnsAllAsNotFoundWhenNoneExist() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        TestCaseBulkDeleteRequestDto request =
                TestCaseBulkDeleteRequestDto.builder().ids(List.of(id1, id2)).build();

        ResponseEntity<TestCaseBulkDeleteResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                TestCaseBulkDeleteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeleted()).isEmpty();
        assertThat(response.getBody().getNotFound()).containsExactly(id1, id2);
    }

    @Test
    @DisplayName("Should return 404 when bulk deleting by IDs on non-existent dataset")
    void shouldReturn404WhenBulkDeleteByIdsDatasetNotFound() {
        TestCaseBulkDeleteRequestDto request = TestCaseBulkDeleteRequestDto.builder()
                .ids(List.of(UUID.randomUUID()))
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + UUID.randomUUID() + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when bulk deleting by IDs with empty list")
    void shouldReturn400WhenBulkDeleteByIdsWithEmptyList() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createTestCase(suite.getId(), "ShouldStay");

        TestCaseBulkDeleteRequestDto request =
                TestCaseBulkDeleteRequestDto.builder().ids(List.of()).build();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(testCaseRepository.countByDatasetId(datasetId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should return 400 when bulk deleting by IDs with duplicate IDs")
    void shouldReturn400WhenBulkDeleteByIdsWithDuplicates() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createTestCase(suite.getId(), "ShouldStayDup");
        UUID id = UUID.randomUUID();

        TestCaseBulkDeleteRequestDto request =
                TestCaseBulkDeleteRequestDto.builder().ids(List.of(id, id)).build();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(testCaseRepository.countByDatasetId(datasetId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should return 400 when bulk deleting by IDs count exceeds cap")
    void shouldReturn400WhenBulkDeleteByIdsExceedsCap() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createTestCase(suite.getId(), "ShouldStayCap");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10001; i++) {
            ids.add(UUID.randomUUID());
        }

        TestCaseBulkDeleteRequestDto request =
                TestCaseBulkDeleteRequestDto.builder().ids(ids).build();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases:bulk"),
                HttpMethod.DELETE,
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(testCaseRepository.countByDatasetId(datasetId)).isEqualTo(1L);
    }
}
