package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetVisibilityTransitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
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

@DisplayName("Dataset Visibility Functional Tests")
public abstract class DatasetVisibilityFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    // -----------------------------------------------------------------------
    // create — PUBLIC vs PRIVATE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /datasets with visibility=PUBLIC persists the dataset with PUBLIC visibility")
    void createPublicSucceeds() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Visibility-Public-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PUBLIC)
                .build();

        ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);

        Dataset persisted =
                datasetRepository.findById(response.getBody().getId()).orElseThrow();
        assertThat(persisted.getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
    }

    @Test
    @DisplayName("POST /datasets with visibility=PRIVATE and valid bindToSuiteId atomically rebinds the target suite")
    void createPrivateBindsTargetSuiteAtomically() {
        TestSuite targetSuite = metaTestDataHelper.createTestSuite("Visibility-Bind-Target-" + UUID.randomUUID());
        UUID originalDatasetId = targetSuite.getDatasetId();

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Visibility-Private-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(targetSuite.getId())
                .build();

        ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        UUID newDatasetId = response.getBody().getId();
        assertThat(response.getBody().getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);

        TestSuite rebound = testSuiteRepository.findById(targetSuite.getId()).orElseThrow();
        assertThat(rebound.getDatasetId())
                .as("suite must be rebound atomically to the new PRIVATE dataset")
                .isEqualTo(newDatasetId)
                .isNotEqualTo(originalDatasetId);
    }

    @Test
    @DisplayName(
            "POST /datasets with visibility=PRIVATE without bindToSuiteId returns 400 (PRIVATE_DATASET_REQUIRES_SUITE_BINDING)")
    void createPrivateWithoutBindingReturns400() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Visibility-Private-NoBind-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PRIVATE)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PRIVATE_DATASET_REQUIRES_SUITE_BINDING");
    }

    @Test
    @DisplayName(
            "POST /datasets with visibility=PUBLIC and bindToSuiteId returns 400 (PUBLIC_DATASET_FORBIDS_SUITE_BINDING)")
    void createPublicWithBindingReturns400() {
        TestSuite suite = metaTestDataHelper.createTestSuite("Visibility-Public-Bind-" + UUID.randomUUID());

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Visibility-Public-Bind-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PUBLIC)
                .bindToSuiteId(suite.getId())
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PUBLIC_DATASET_FORBIDS_SUITE_BINDING");
    }

    @Test
    @DisplayName(
            "POST /datasets with visibility=PRIVATE targeting a suite already bound to another PRIVATE dataset returns 409 (PRIVATE_DATASET_ALREADY_BOUND)")
    void createPrivateForSuiteWithOtherPrivateReturns409() {
        TestSuite suite = metaTestDataHelper.createTestSuite("Visibility-AlreadyBound-" + UUID.randomUUID());

        DatasetRequestDto first = DatasetRequestDto.builder()
                .name("Private-A-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(suite.getId())
                .build();
        ResponseEntity<DatasetResponseDto> firstResponse =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(first), DatasetResponseDto.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        DatasetRequestDto second = DatasetRequestDto.builder()
                .name("Private-B-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(suite.getId())
                .build();
        ResponseEntity<String> secondResponse =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(second), String.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResponse.getBody()).contains("PRIVATE_DATASET");
    }

    // -----------------------------------------------------------------------
    // list — server-side hard filter hides PRIVATE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /datasets hides PRIVATE datasets but GET /datasets/{id} returns them by id")
    void listExcludesPrivateButGetByIdReturnsIt() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Visibility-Private-Hidden-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        // Bind a freshly created suite directly to the PRIVATE dataset via the helper's
        // @Transactional path so the trigger and timestamp aspect both fire correctly.
        metaTestDataHelper.createTestSuite("Visibility-List-Hidden-" + UUID.randomUUID(), privateDs.getId());

        ResponseEntity<PageResponseDto<DatasetResponseDto>> listResponse = restTemplate.exchange(
                apiUrl("/datasets?page=0&size=200&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody().getContent())
                .as("PRIVATE dataset must not appear in the list endpoint")
                .noneMatch(d -> privateDs.getId().equals(d.getId()));

        ResponseEntity<DatasetResponseDto> byId =
                restTemplate.getForEntity(apiUrl("/datasets/" + privateDs.getId()), DatasetResponseDto.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).isNotNull();
        assertThat(byId.getBody().getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
    }

    @Test
    @DisplayName("GET /datasets?filter=visibility:eq:PRIVATE returns 400 — visibility is not in the filter whitelist")
    void filterByVisibilityReturns400() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/datasets?filter=visibility:eq:PRIVATE"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // PATCH /datasets/{id}/visibility — transitions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "PATCH /datasets/{id}/visibility PUBLIC→PRIVATE with 0 bindings returns 409 (PRIVATE_TRANSITION_INVALID_BINDING_COUNT)")
    void transitionPublicToPrivateZeroBindingsReturns409() {
        Dataset dataset = metaTestDataHelper.createDataset(
                "Visibility-Transit-0-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);

        ResponseEntity<String> response = patchVisibility(dataset.getId(), DatasetVisibility.PRIVATE, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PRIVATE_TRANSITION_INVALID_BINDING_COUNT");
    }

    @Test
    @DisplayName(
            "PATCH /datasets/{id}/visibility PUBLIC→PRIVATE with exactly 1 binding returns 200 and persists the new visibility")
    void transitionPublicToPrivateOneBindingReturns200() {
        Dataset dataset = metaTestDataHelper.createDataset(
                "Visibility-Transit-1-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite = metaTestDataHelper.createTestSuite("Suite-Transit-" + UUID.randomUUID(), dataset.getId());
        assertThat(suite.getDatasetId()).isEqualTo(dataset.getId());

        ResponseEntity<DatasetResponseDto> response =
                patchVisibility(dataset.getId(), DatasetVisibility.PRIVATE, DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);

        Dataset refreshed = datasetRepository.findById(dataset.getId()).orElseThrow();
        assertThat(refreshed.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
    }

    @Test
    @DisplayName(
            "PATCH /datasets/{id}/visibility PUBLIC→PRIVATE with 2+ bindings returns 409 (PRIVATE_TRANSITION_INVALID_BINDING_COUNT)")
    void transitionPublicToPrivateMultipleBindingsReturns409() {
        Dataset dataset = metaTestDataHelper.createDataset(
                "Visibility-Transit-2-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        metaTestDataHelper.createTestSuite("Suite-A-" + UUID.randomUUID(), dataset.getId());
        metaTestDataHelper.createTestSuite("Suite-B-" + UUID.randomUUID(), dataset.getId());

        ResponseEntity<String> response = patchVisibility(dataset.getId(), DatasetVisibility.PRIVATE, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PRIVATE_TRANSITION_INVALID_BINDING_COUNT");
    }

    @Test
    @DisplayName(
            "PATCH /datasets/{id}/visibility PRIVATE→PUBLIC always succeeds (no binding-count check) and bumps version")
    void transitionPrivateToPublicAlwaysSucceeds() {
        Dataset dataset = metaTestDataHelper.createDataset(
                "Visibility-Transit-P2P-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        Long versionBefore = dataset.getVersion();

        ResponseEntity<DatasetResponseDto> response =
                patchVisibility(dataset.getId(), DatasetVisibility.PUBLIC, DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        assertThat(response.getBody().getVersion())
                .as("version should bump on visibility change")
                .isGreaterThan(versionBefore == null ? 0L : versionBefore);
    }

    // -----------------------------------------------------------------------
    // DELETE /datasets/{id} — PUBLIC RESTRICT vs PRIVATE cascade
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /datasets/{id} on PUBLIC dataset with no bound suites → 204; with bound suites → 409")
    void deletePublicDatasetRespectsRestrict() {
        Dataset standalone = metaTestDataHelper.createDataset(
                "Visibility-Del-Public-Free-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        ResponseEntity<Void> okDelete =
                restTemplate.exchange(apiUrl("/datasets/" + standalone.getId()), HttpMethod.DELETE, null, Void.class);
        assertThat(okDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(datasetRepository.findById(standalone.getId())).isEmpty();

        Dataset bound = metaTestDataHelper.createDataset(
                "Visibility-Del-Public-Bound-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        metaTestDataHelper.createTestSuite("Suite-Del-Public-" + UUID.randomUUID(), bound.getId());
        ResponseEntity<String> conflict =
                restTemplate.exchange(apiUrl("/datasets/" + bound.getId()), HttpMethod.DELETE, null, String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(datasetRepository.findById(bound.getId())).isPresent();
    }

    @Test
    @DisplayName(
            "DELETE /datasets/{id} on PRIVATE dataset → 204; suite remains with datasetId=null, dataset row and test cases gone")
    void deletePrivateDatasetCascadesUnbindAndDelete() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Visibility-Del-Private-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Suite-Del-Private-" + UUID.randomUUID(), privateDs.getId());
        metaTestDataHelper.seedManyTestCasesInDataset(privateDs.getId(), 3, true);

        ResponseEntity<Void> response =
                restTemplate.exchange(apiUrl("/datasets/" + privateDs.getId()), HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(datasetRepository.findById(privateDs.getId())).isEmpty();
        assertThat(testCaseRepository.countByDatasetId(privateDs.getId())).isZero();
        TestSuite refreshed = testSuiteRepository.findById(suite.getId()).orElseThrow();
        assertThat(refreshed.getDatasetId())
                .as("PRIVATE delete must unbind the suite, not cascade-delete it")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // DELETE /test-suites/{id} — PUBLIC vs PRIVATE cascade
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "DELETE /test-suites/{id} on PRIVATE-bound suite → suite, dataset, and test cases all removed in one transaction")
    void deletePrivateBoundSuiteCascadesDataset() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Visibility-SuiteDel-Private-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Suite-PrivateDel-" + UUID.randomUUID(), privateDs.getId());
        metaTestDataHelper.seedManyTestCasesInDataset(privateDs.getId(), 2, true);

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/test-suites/" + suite.getId()), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testSuiteRepository.findById(suite.getId())).isEmpty();
        assertThat(datasetRepository.findById(privateDs.getId()))
                .as("PRIVATE dataset must cascade-delete with its owning suite")
                .isEmpty();
        assertThat(testCaseRepository.countByDatasetId(privateDs.getId())).isZero();
    }

    @Test
    @DisplayName("DELETE /test-suites/{id} on PUBLIC-bound suite → suite removed, dataset preserved")
    void deletePublicBoundSuiteKeepsDataset() {
        Dataset publicDs = metaTestDataHelper.createDataset(
                "Visibility-SuiteDel-Public-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite = metaTestDataHelper.createTestSuite("Suite-PublicDel-" + UUID.randomUUID(), publicDs.getId());

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/test-suites/" + suite.getId()), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testSuiteRepository.findById(suite.getId())).isEmpty();
        assertThat(datasetRepository.findById(publicDs.getId()))
                .as("PUBLIC dataset must survive its bound suite's delete")
                .isPresent();
    }

    // -----------------------------------------------------------------------
    // PATCH /test-suites/{id} — rebind/unbind from PRIVATE forbidden
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "PUT /test-suites/{id} rebinding away from PRIVATE → 409 (PRIVATE_DATASET_REBIND_FORBIDDEN); unbinding (datasetId=null) → 409 too")
    void rebindOrUnbindFromPrivateReturns409() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Visibility-Rebind-Private-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        Dataset otherPublic = metaTestDataHelper.createDataset(
                "Visibility-Rebind-Other-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite = metaTestDataHelper.createTestSuite("Suite-Rebind-" + UUID.randomUUID(), privateDs.getId());

        // Rebind to a different dataset
        ResponseEntity<String> rebind = putSuite(
                suite.getId(),
                suite.getVersion(),
                TestSuiteRequestDto.builder()
                        .name(suite.getName())
                        .datasetId(otherPublic.getId())
                        .build());
        assertThat(rebind.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rebind.getBody()).contains("PRIVATE_DATASET_REBIND_FORBIDDEN");

        // Unbind (datasetId=null)
        ResponseEntity<String> unbind = putSuite(
                suite.getId(),
                suite.getVersion(),
                TestSuiteRequestDto.builder()
                        .name(suite.getName())
                        .datasetId(null)
                        .build());
        assertThat(unbind.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unbind.getBody()).contains("PRIVATE_DATASET_REBIND_FORBIDDEN");

        // Neither attempt mutated the suite
        TestSuite refreshed = testSuiteRepository.findById(suite.getId()).orElseThrow();
        assertThat(refreshed.getDatasetId()).isEqualTo(privateDs.getId());
    }

    // -----------------------------------------------------------------------
    // Unbound suite — create, retrieve, run-start guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "Unbound suite (datasetId=null) is retrievable and updatable, but POST /runs returns 409 SUITE_HAS_NO_DATASET")
    void unboundSuiteIsRetrievableButCannotRun() {
        TestSuite suite = metaTestDataHelper.createTestSuite("Visibility-Unbound-" + UUID.randomUUID(), null);
        assertThat(suite.getDatasetId()).isNull();

        ResponseEntity<TestSuiteResponseDto> get =
                restTemplate.getForEntity(apiUrl("/test-suites/" + suite.getId()), TestSuiteResponseDto.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).isNotNull();
        assertThat(get.getBody().getDatasetId()).isNull();

        ResponseEntity<String> run = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(run.getBody()).contains("SUITE_HAS_NO_DATASET");
    }

    @Test
    @DisplayName(
            "Run-start guard fires before validity check: an unbound + invalid suite still reports SUITE_HAS_NO_DATASET")
    void runStartGuardFiresBeforeValidityCheck() {
        TestSuite suite = metaTestDataHelper.createTestSuite("Visibility-Unbound-Invalid-" + UUID.randomUUID(), null);
        metaTestDataHelper.forceSuiteInvalid(suite.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .as("dataset check must run before validity check — deterministic ordering")
                .contains("SUITE_HAS_NO_DATASET")
                .doesNotContain("SUITE_NOT_VALID");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private <T> ResponseEntity<T> patchVisibility(UUID datasetId, DatasetVisibility target, Class<T> responseType) {
        DatasetVisibilityTransitionDto body =
                DatasetVisibilityTransitionDto.builder().visibility(target).build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/visibility"),
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                responseType);
    }

    private ResponseEntity<String> putSuite(UUID suiteId, Long expectedVersion, TestSuiteRequestDto body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + (expectedVersion == null ? 0L : expectedVersion) + "\"");
        return restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId), HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unused")
    private TestSuiteResponseDto postSuite(TestSuiteRequestDto body) {
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(body), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
