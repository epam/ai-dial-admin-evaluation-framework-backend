package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Tests the relocated dataset-scoped test-case endpoints at {@code /datasets/{datasetId}/test-cases}.
 * Other functional-test files exercise the full surface (bulk-patch, CSV import/export round-trip,
 * batch put/patch); this file focuses on a focused happy-path verification that the dataset-scoped
 * URL shape works end-to-end for the most common operations: POST, GET-by-id, GET-list, PUT
 * full-update, PATCH partial-update, DELETE, and CSV export.
 */
@DisplayName("Dataset-scoped TestCase Functional Tests")
public abstract class DatasetScopedTestCaseFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Test
    @DisplayName("POST /datasets/{datasetId}/test-cases creates a test case and returns 201")
    void createReturns201() {
        Dataset dataset = metaTestDataHelper.createDataset("Scoped-Create-" + UUID.randomUUID());
        TestCaseRequestDto request = TestCaseRequestDto.builder()
                .testCaseName("Case A")
                .data(Map.of("query", "hello"))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases"), jsonEntity(request), TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getTestCaseName()).isEqualTo("Case A");
        assertThat(response.getBody().getData()).containsEntry("query", "hello");
    }

    @Test
    @DisplayName("GET /datasets/{datasetId}/test-cases lists test cases scoped to the dataset")
    void listReturnsDatasetScoped() {
        Dataset a = metaTestDataHelper.createDataset("Scoped-List-A-" + UUID.randomUUID());
        Dataset b = metaTestDataHelper.createDataset("Scoped-List-B-" + UUID.randomUUID());
        seedCase(a.getId(), "tc-A1");
        seedCase(a.getId(), "tc-A2");
        seedCase(b.getId(), "tc-B1");

        ResponseEntity<PageResponseDto<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + a.getId() + "/test-cases?page=0&size=10&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalElements()).isEqualTo(2L);
        assertThat(response.getBody().getContent())
                .extracting(TestCaseResponseDto::getTestCaseName)
                .containsExactlyInAnyOrder("tc-A1", "tc-A2");
    }

    @Test
    @DisplayName("GET /datasets/{datasetId}/test-cases/{id} returns the test case")
    void getByIdReturnsCase() {
        Dataset dataset = metaTestDataHelper.createDataset("Scoped-Get-" + UUID.randomUUID());
        UUID caseId = seedCase(dataset.getId(), "Get Me");

        ResponseEntity<TestCaseResponseDto> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/" + caseId), TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(caseId);
        assertThat(response.getBody().getTestCaseName()).isEqualTo("Get Me");
    }

    @Test
    @DisplayName("PATCH /datasets/{datasetId}/test-cases/{id} updates only supplied fields")
    void patchUpdatesFields() {
        Dataset dataset = metaTestDataHelper.createDataset("Scoped-Patch-" + UUID.randomUUID());
        UUID caseId = seedCase(dataset.getId(), "Original");

        TestCaseRequestDto patch =
                TestCaseRequestDto.builder().testCaseName("Renamed").build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/" + caseId),
                HttpMethod.PATCH,
                jsonEntity(patch),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTestCaseName()).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("DELETE /datasets/{datasetId}/test-cases/{id} removes the test case")
    void deleteRemovesCase() {
        Dataset dataset = metaTestDataHelper.createDataset("Scoped-Delete-" + UUID.randomUUID());
        UUID caseId = seedCase(dataset.getId(), "To Delete");

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/" + caseId), HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResp = restTemplate.getForEntity(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/" + caseId), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CSV import + export round-trip preserves the test case via the dataset-scoped endpoints")
    void csvImportExportRoundTrip() {
        Dataset dataset = metaTestDataHelper.createDataset(
                "Scoped-CSV-" + UUID.randomUUID(), "[{\"name\":\"query\",\"type\":\"STRING\",\"required\":true}]");

        // Import a single-row CSV
        String csv = "testCaseName,query\nImported Case,hi there\n";
        HttpHeaders importHeaders = new HttpHeaders();
        importHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource csvBytes =
                new org.springframework.core.io.ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "import.csv";
                    }
                };
        parts.add("file", csvBytes);
        parts.add("delimiter", ",");
        parts.add("mode", "INSERT_OR_SKIP");
        ResponseEntity<String> importResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/import"),
                HttpMethod.POST,
                new HttpEntity<>(parts, importHeaders),
                String.class);
        assertThat(importResp.getStatusCode().is2xxSuccessful()).isTrue();

        // Verify list now contains the imported case
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> listResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases?page=0&size=10&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getBody()).isNotNull();
        assertThat(listResp.getBody().getTotalElements()).isEqualTo(1L);
        assertThat(listResp.getBody().getContent().get(0).getTestCaseName()).isEqualTo("Imported Case");

        // Export back as CSV
        ResponseEntity<byte[]> exportResp = restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId() + "/test-cases/export.csv"), HttpMethod.GET, null, byte[].class);
        assertThat(exportResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exportResp.getBody()).isNotNull();
        String exported = new String(exportResp.getBody(), StandardCharsets.UTF_8);
        assertThat(exported).contains("Imported Case");
        assertThat(exported).contains("hi there");
    }

    private UUID seedCase(UUID datasetId, String name) {
        ResponseEntity<TestCaseResponseDto> resp = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(Map.of("query", "x"))
                        .build()),
                TestCaseResponseDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().getId();
    }
}
