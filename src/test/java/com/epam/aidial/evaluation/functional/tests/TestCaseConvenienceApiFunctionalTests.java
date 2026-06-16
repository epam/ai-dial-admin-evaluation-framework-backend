package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Test Case Convenience API Functional Tests (template-variables + resolved-request)")
public abstract class TestCaseConvenienceApiFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    // Note: per-test-case template-variables endpoint was removed in task group 11
    // (TemplateVariableService simplified to suite-scoped only); its tests have been deleted in bulk.

    // --- Resolved-request API (10.5) ---

    @Test
    @DisplayName("Should resolve request with data values")
    void shouldResolveRequestWithDataValues() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        TestCaseResponseDto tc = createTestCase(suite, "TC Resolve", Map.of("promptField", "Tell me about AI"));

        ResponseEntity<ResolvedRequestDto> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/resolved-request"),
                ResolvedRequestDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUrl()).isEqualTo("/v1/chat");
        assertThat(response.getBody().getBody()).isNotNull();
        assertThat(((ResolvedJsonBodyDto) response.getBody().getBody())
                        .getContent()
                        .get("prompt"))
                .isEqualTo("Tell me about AI");
        // temperature should use default 0.7
        assertThat(((ResolvedJsonBodyDto) response.getBody().getBody())
                        .getContent()
                        .get("temperature"))
                .isEqualTo("0.7");
    }

    @Test
    @DisplayName("Should produce warnings for missing data")
    void shouldProduceWarningsForMissingData() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        // Create test case without the required promptField
        TestCaseResponseDto tc = createTestCase(suite, "TC Missing", Map.of());

        ResponseEntity<ResolvedRequestDto> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/resolved-request"),
                ResolvedRequestDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("Should return resolved request with no template (warnings)")
    void shouldReturnWarningsForNoTemplate() {
        TestSuiteResponseDto suite = createSuiteWithoutTemplate();
        TestCaseResponseDto tc = createTestCase(suite, "TC No Template", Map.of("col1", "value"));

        ResponseEntity<ResolvedRequestDto> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/resolved-request"),
                ResolvedRequestDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWarnings()).isNotEmpty();
        assertThat(response.getBody().getUrl()).isNull();
    }

    @Test
    @DisplayName("Should return 404 for non-existent test case resolved-request")
    void shouldReturn404ForNonExistentTestCaseResolvedRequest() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + UUID.randomUUID() + "/resolved-request"),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Helpers ---

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("conv-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private TestSuiteResponseDto createSuiteWithTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}", "temperature", "${{temperature:0.7}}"))
                        .build())
                .build();
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Convenience Suite " + UUID.randomUUID())
                .description("Suite with template")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
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

    private TestSuiteResponseDto createSuiteWithoutTemplate() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("No Template Suite " + UUID.randomUUID())
                .description("Suite without template")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("col1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private TestCaseResponseDto createTestCase(TestSuiteResponseDto suite, String name, Map<String, Object> data) {
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> res = restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                jsonEntity(req),
                TestCaseResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("deployment-1")
                .name("D1")
                .version("v1")
                .build();
    }

    private EndpointContractDto buildEndpoint() {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/chat")
                .parameters(List.of(ParameterDefinitionDto.builder()
                        .name("query")
                        .in(ParameterLocation.QUERY)
                        .required(true)
                        .schema(Map.of("type", "string"))
                        .build()))
                .requestBodySchema(JsonRequestBodySchemaDto.builder()
                        .schema(Map.of("type", "object", "properties", Map.of("prompt", Map.of("type", "string"))))
                        .build())
                .build();
    }
}
