package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared helpers for batch PUT and batch PATCH functional tests.
 */
public abstract class BaseTestCaseBatchFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    protected TestSuiteResponseDto createTestSuite() {
        Dataset dataset = metaTestDataHelper.createDataset("ds-batch-" + UUID.randomUUID());
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite for Batch " + UUID.randomUUID())
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

    protected TestSuiteResponseDto createTestSuiteWithSchema() {
        String schemaJson = jsonOf(List.of(
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
        Dataset dataset = metaTestDataHelper.createDataset("ds-batch-schema-" + UUID.randomUUID(), schemaJson);
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Suite with schema " + UUID.randomUUID())
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
        return createTestCaseWithData(testSuiteId, name, Map.of());
    }

    protected TestCaseResponseDto createTestCaseWithData(UUID testSuiteId, String name, Map<String, Object> data) {
        TestCaseRequestDto req = TestCaseRequestDto.builder()
                .testCaseName(name)
                .data(data != null ? data : Map.of())
                .build();
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private String jsonOf(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize test fixture", e);
        }
    }
}
