package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Shared helpers for composite bulk-patch functional tests.
 */
public abstract class BaseTestCaseBulkPatchFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected TestCaseRepository testCaseRepository;

    protected TestSuiteResponseDto createTestSuite() {
        Dataset dataset = metaTestDataHelper.createDataset("ds-bulk-" + UUID.randomUUID());
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite for Bulk Patch " + UUID.randomUUID())
                .description("Desc")
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

    protected TestCaseResponseDto createTestCase(UUID testSuiteId, String name) {
        return createTestCase(testSuiteId, name, true);
    }

    /**
     * Per-case {@code enabled} is gone (replaced by suite-level {@code disabledTestCaseIds}). When
     * {@code enabled=false}, the caller wants the new case to appear in the suite's disabled list,
     * so the helper appends the created id to {@code disabled_test_case_ids} after creation.
     */
    protected TestCaseResponseDto createTestCase(UUID testSuiteId, String name, boolean enabled) {
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(Map.of()).build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto created = r.getBody();
        if (!enabled) {
            metaTestDataHelper.appendDisabledTestCaseIds(testSuiteId, List.of(created.getId()));
        }
        return created;
    }

    protected List<UUID> seedManyTestCases(UUID testSuiteId, int count, boolean enabled) {
        return metaTestDataHelper.seedManyTestCases(testSuiteId, count, enabled);
    }

    protected String bulkUrl(UUID testSuiteId) {
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        return apiUrl("/datasets/" + datasetId + "/test-cases:bulk");
    }
}
