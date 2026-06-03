package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for TestSuite ↔ Dataset binding semantics post-{@code introduce-dataset-entity}:
 * suite create with {@code datasetId}, update rebinding to a different dataset, delete leaves dataset
 * intact, and {@code disabledTestCaseIds} round-trips through the REST API.
 */
@DisplayName("TestSuite Dataset-Binding Functional Tests")
public abstract class TestSuiteDatasetFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private DatasetRepository datasetRepository;

    @Test
    @DisplayName("POST /test-suites with datasetId persists the binding")
    void createWithDatasetIdPersistsBinding() {
        Dataset dataset = metaTestDataHelper.createDataset("Suite-Bind-" + UUID.randomUUID());

        TestSuiteRequestDto request = baseSuiteRequest("Suite-" + UUID.randomUUID(), dataset.getId());

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDatasetId()).isEqualTo(dataset.getId());
        assertThat(response.getBody().getDisabledTestCaseIds()).isEmpty();
    }

    @Test
    @DisplayName("PUT /test-suites/{id} rebinding to a different dataset updates the datasetId")
    void updateRebindsToDifferentDataset() {
        Dataset originalDataset = metaTestDataHelper.createDataset("Original-" + UUID.randomUUID());
        Dataset newDataset = metaTestDataHelper.createDataset("Rebind-Target-" + UUID.randomUUID());

        TestSuiteResponseDto suite = createSuite(originalDataset.getId());

        TestSuiteRequestDto update = baseSuiteRequest(suite.getName(), newDataset.getId());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + suite.getVersion() + "\"");

        ResponseEntity<TestSuiteResponseDto> updateResp = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                TestSuiteResponseDto.class);

        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody()).isNotNull();
        assertThat(updateResp.getBody().getDatasetId()).isEqualTo(newDataset.getId());
        // Both datasets still exist after rebind
        assertThat(datasetRepository.existsById(originalDataset.getId())).isTrue();
        assertThat(datasetRepository.existsById(newDataset.getId())).isTrue();
    }

    @Test
    @DisplayName("DELETE /test-suites/{id} leaves the bound dataset intact")
    void deleteSuiteLeavesDatasetIntact() {
        Dataset dataset = metaTestDataHelper.createDataset("Suite-Delete-" + UUID.randomUUID());
        TestSuiteResponseDto suite = createSuite(dataset.getId());

        ResponseEntity<Void> deleteResp =
                restTemplate.exchange(apiUrl("/test-suites/" + suite.getId()), HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResp.getStatusCode().is2xxSuccessful()).isTrue();

        // Dataset still exists
        assertThat(datasetRepository.existsById(dataset.getId())).isTrue();
    }

    @Test
    @DisplayName("disabledTestCaseIds round-trip: PUT writes, GET reads back the list")
    void disabledTestCaseIdsRoundTrip() {
        Dataset dataset = metaTestDataHelper.createDataset("Disabled-RoundTrip-" + UUID.randomUUID());
        TestSuiteResponseDto suite = createSuite(dataset.getId());

        UUID disabled1 = UUID.randomUUID();
        UUID disabled2 = UUID.randomUUID();
        TestSuiteRequestDto update = baseSuiteRequest(suite.getName(), dataset.getId());
        update.setDisabledTestCaseIds(List.of(disabled1, disabled2));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + suite.getVersion() + "\"");
        ResponseEntity<TestSuiteResponseDto> updateResp = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                TestSuiteResponseDto.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<TestSuiteResponseDto> getResp =
                restTemplate.getForEntity(apiUrl("/test-suites/" + suite.getId()), TestSuiteResponseDto.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().getDisabledTestCaseIds()).containsExactlyInAnyOrder(disabled1, disabled2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TestSuiteRequestDto baseSuiteRequest(String name, UUID datasetId) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("desc")
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(org.springframework.http.HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .datasetId(datasetId)
                .build();
    }

    private TestSuiteResponseDto createSuite(UUID datasetId) {
        TestSuiteRequestDto request = baseSuiteRequest("Suite-" + UUID.randomUUID(), datasetId);
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
