package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
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

@DisplayName("Template-Binding Edge Case Functional Tests")
public abstract class TemplateBindingEdgeCaseFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    // --- Suite-level validation edge cases ---

    @Test
    @DisplayName("Should mark suite invalid when required variable has no binding")
    void shouldMarkSuiteInvalidWhenRequiredVariableHasNoBinding() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}", "context", "${{context}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Missing Binding Suite " + UUID.randomUUID())
                .description("Has required variable without binding")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("promptField")
                                .build()
                        // "context" has no binding and no default
                        ))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        assertThat(suite.isValid()).isFalse();
        assertThat(suite.getValidationWarnings()).isNotEmpty();
        assertThat(suite.getValidationWarnings())
                .anyMatch(w -> w.getFieldName() != null
                        && w.getFieldName().equals("context")
                        && w.getCode() == ValidationWarningCode.REQUIRED);
    }

    @Test
    @DisplayName("Should mark suite invalid when orphan binding exists")
    void shouldMarkSuiteInvalidWhenOrphanBindingExists() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Orphan Binding Suite " + UUID.randomUUID())
                .description("Has binding with no matching template variable")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("promptField")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("unusedField")
                                .type(SchemaFieldType.STRING)
                                .build())))
                .requestTemplate(template)
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("promptField")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("nonExistent")
                                .dataField("unusedField")
                                .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        assertThat(suite.isValid()).isFalse();
        assertThat(suite.getValidationWarnings())
                .anyMatch(w -> w.getFieldName() != null
                        && w.getFieldName().equals("nonExistent")
                        && w.getCode() == ValidationWarningCode.ADDITIONAL);
    }

    @Test
    @DisplayName("Should mark suite invalid when binding maps to unknown schema field")
    void shouldMarkSuiteInvalidWhenBindingMapsToUnknownField() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Unknown Field Binding Suite " + UUID.randomUUID())
                .description("Binding maps to field not in dataset's testCaseSchema")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("otherField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("missingSchemaField")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        assertThat(suite.isValid()).isFalse();
        assertThat(suite.getValidationWarnings())
                .anyMatch(w -> w.getFieldName() != null
                        && w.getFieldName().equals("missingSchemaField")
                        && w.getCode() == ValidationWarningCode.UNKNOWN);
    }

    @Test
    @DisplayName("Should mark suite valid when variable has default and no binding")
    void shouldMarkSuiteValidWhenVariableHasDefaultAndNoBinding() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("temperature", "${{temperature:0.7}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Default No Binding Suite " + UUID.randomUUID())
                .description("Variable with default needs no binding")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newEmptyDatasetId())
                .requestTemplate(template)
                .inputBindings(List.of())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        assertSuiteConfigValid(suite);
        assertThat(configWarnings(suite)).isEmpty();
    }

    @Test
    @DisplayName("Should mark suite valid with constant value binding")
    void shouldMarkSuiteValidWithConstantValueBinding() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("model", "${{model}}", "prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Constant Binding Suite " + UUID.randomUUID())
                .description("Binding uses constantValue instead of dataField")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("model")
                                .constantValue("gpt-4")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("promptField")
                                .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        assertSuiteConfigValid(suite);
    }

    // --- Test-case-level validation edge cases ---

    @Test
    @DisplayName("Should mark test case invalid when required data field is empty")
    void shouldMarkTestCaseInvalidWhenRequiredDataFieldIsEmpty() {
        TestSuiteResponseDto suite = createValidSuite();

        TestCaseRequestDto tcReq = TestCaseRequestDto.builder()
                .testCaseName("Missing Data TC")
                .data(Map.of()) // No promptField value
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases?includeWarnings=true"),
                jsonEntity(tcReq),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto tc = response.getBody();
        assertThat(tc).isNotNull();
        assertThat(tc.isValid()).isFalse();
        assertThat(tc.getValidationWarnings()).isNotEmpty();
        assertThat(tc.getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                        && w.getFieldName() != null
                        && w.getFieldName().equals("promptField"));
    }

    @Test
    @DisplayName("Should mark test case valid when all required fields are provided")
    void shouldMarkTestCaseValidWhenAllRequiredFieldsProvided() {
        TestSuiteResponseDto suite = createValidSuite();

        TestCaseRequestDto tcReq = TestCaseRequestDto.builder()
                .testCaseName("Complete Data TC")
                .data(Map.of("promptField", "Hello world"))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases?includeWarnings=true"),
                jsonEntity(tcReq),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto tc = response.getBody();
        assertThat(tc).isNotNull();
        assertThat(tc.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should warn about unknown data fields not in schema")
    void shouldWarnAboutUnknownDataFields() {
        TestSuiteResponseDto suite = createValidSuite();

        TestCaseRequestDto tcReq = TestCaseRequestDto.builder()
                .testCaseName("Extra Data TC")
                .data(Map.of("promptField", "Hello", "extraField", "unexpected"))
                .build();

        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases?includeWarnings=true"),
                jsonEntity(tcReq),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto tc = response.getBody();
        assertThat(tc).isNotNull();
        assertThat(tc.isValid()).isFalse();
        assertThat(tc.getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.ADDITIONAL
                        && w.getFieldName() != null
                        && w.getFieldName().equals("extraField"));
    }

    @Test
    @DisplayName("Should reject suite with duplicate templateVariable in inputBindings")
    void shouldRejectSuiteWithDuplicateTemplateVariableInBindings() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Dup Binding Suite " + UUID.randomUUID())
                .description("Duplicate templateVariable")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("f1")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("f2")
                                .type(SchemaFieldType.STRING)
                                .build())))
                .requestTemplate(template)
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("f1")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("f2")
                                .build()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Duplicate");
        assertThat(response.getBody()).contains("prompt");
    }

    @Test
    @DisplayName("Should mark suite valid with no template and no bindings")
    void shouldMarkSuiteWithWarningsWhenNoTemplate() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("No Template Suite " + UUID.randomUUID())
                .description("No template configured")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("col1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();
        // Suite without template should have a warning about missing requestTemplate
        assertThat(suite.isValid()).isFalse();
        assertThat(suite.getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                        && w.getMessage() != null
                        && w.getMessage().contains("requestTemplate"));
    }

    @Test
    @DisplayName("Should validate test case with template variables in multiple sections")
    void shouldValidateTestCaseWithMultiSectionTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/api/${{version}}/chat")
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("token")
                        .value("${{apiToken}}")
                        .build()))
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("X-Model")
                        .value("${{model}}")
                        .build()))
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Multi Section Suite " + UUID.randomUUID())
                .description("Variables in url, query, header, body")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("version")
                                .constantValue("v2")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("apiToken")
                                .constantValue("tok-123")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("model")
                                .constantValue("gpt-4")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("promptField")
                                .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> suiteRes =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(suiteRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = suiteRes.getBody();
        assertThat(suite).isNotNull();
        assertSuiteConfigValid(suite);

        // Create a test case with all data satisfied
        TestCaseRequestDto tcReq = TestCaseRequestDto.builder()
                .testCaseName("Multi Section TC")
                .data(Map.of("promptField", "Hello world"))
                .build();

        ResponseEntity<TestCaseResponseDto> tcRes = restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases?includeWarnings=true"),
                jsonEntity(tcReq),
                TestCaseResponseDto.class);

        assertThat(tcRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto tc = tcRes.getBody();
        assertThat(tc).isNotNull();
        assertThat(tc.isValid()).isTrue();
    }

    // --- Helpers ---

    private UUID newEmptyDatasetId() {
        Dataset dataset = metaTestDataHelper.createDataset("tbedge-" + UUID.randomUUID());
        return dataset.getId();
    }

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("tbedge-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private TestSuiteResponseDto createValidSuite() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Edge Case Suite " + UUID.randomUUID())
                .description("Suite for edge case tests")
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

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("d1")
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
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .build())
                .build();
    }
}
