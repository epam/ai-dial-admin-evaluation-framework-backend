package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Shared helpers for Grafana integration functional tests.
 */
public abstract class AbstractGrafanaFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    protected UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("grafana-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    protected void insertResultWithTraceId(UUID testSuiteId, UUID runId, String traceId) {
        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-grafana")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode().put("prompt", "test"))
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId(traceId)
                        .build())
                .build();
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(runId)
                .results(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
    }

    protected TestSuiteResponseDto createSuiteWithTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/chat/completions")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Grafana Try-It-Out Suite " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("D1")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/chat/completions")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("promptField")
                        .build()))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    protected TestCaseResponseDto createTestCase(UUID testSuiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> res = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }
}
