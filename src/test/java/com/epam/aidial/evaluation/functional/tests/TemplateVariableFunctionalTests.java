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
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableSource;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Template Variable Functional Tests")
public abstract class TemplateVariableFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("tv-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should return template variables for suite with variables")
    void shouldReturnTemplateVariablesForSuiteWithVariables() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isNotEmpty();

        // Check that "prompt" variable is found in BODY
        assertThat(response.getBody())
                .anyMatch(v -> "prompt".equals(v.getName())
                        && v.getSources().contains(TemplateVariableSource.BODY)
                        && !v.isHasDefault());

        // Check that "temperature" variable is found in BODY with default
        assertThat(response.getBody())
                .anyMatch(v -> "temperature".equals(v.getName())
                        && v.getSources().contains(TemplateVariableSource.BODY)
                        && v.isHasDefault()
                        && "0.7".equals(v.getDefaultValue()));
    }

    @Test
    @DisplayName("Should return empty list for suite without template")
    void shouldReturnEmptyListForSuiteWithoutTemplate() {
        TestSuiteResponseDto suite = createSuiteWithoutTemplate();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("Should resolve binding for template variables")
    void shouldResolveBindingForTemplateVariables() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // "prompt" should have binding to "promptField"
        TemplateVariableDto promptVar = response.getBody().stream()
                .filter(v -> "prompt".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertThat(promptVar).isNotNull();
        assertThat(promptVar.getBinding()).isNotNull();
        assertThat(promptVar.getBinding().getDataField()).isEqualTo("promptField");
    }

    @Test
    @DisplayName("Should infer type from testCaseSchema via binding")
    void shouldInferTypeFromTestCaseSchemaViaBinding() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // "prompt" is bound to "promptField" which is STRING in testCaseSchema
        TemplateVariableDto promptVar = response.getBody().stream()
                .filter(v -> "prompt".equals(v.getName()))
                .findFirst()
                .orElse(null);
        assertThat(promptVar).isNotNull();
        assertThat(promptVar.getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test suite")
    void shouldReturn404ForNonExistentTestSuite() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/template-variables"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return variables from multiple template sections")
    void shouldReturnVariablesFromMultipleTemplateSections() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/api/v1/${{version}}/chat")
                .queryParams(List.of(KeyValueTemplateDto.builder()
                        .key("api-key")
                        .value("${{apiKey}}")
                        .build()))
                .headers(List.of(KeyValueTemplateDto.builder()
                        .key("X-Custom")
                        .value("${{headerVal}}")
                        .build()))
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{prompt}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Multi Section Suite " + UUID.randomUUID())
                .description("Suite with variables in all sections")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("promptField")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> createResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID suiteId = createResponse.getBody().getId();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(4);

        assertThat(response.getBody())
                .anyMatch(v -> "version".equals(v.getName()) && v.getSources().contains(TemplateVariableSource.URL));
        assertThat(response.getBody())
                .anyMatch(v -> "apiKey".equals(v.getName()) && v.getSources().contains(TemplateVariableSource.QUERY));
        assertThat(response.getBody())
                .anyMatch(
                        v -> "headerVal".equals(v.getName()) && v.getSources().contains(TemplateVariableSource.HEADER));
        assertThat(response.getBody())
                .anyMatch(v -> "prompt".equals(v.getName()) && v.getSources().contains(TemplateVariableSource.BODY));
    }

    // --- Suite-level resolvedValue tests ---

    @Test
    @DisplayName("Should resolve constant-value binding at suite level")
    void shouldResolveConstantValueBindingAtSuiteLevel() {
        TestSuiteResponseDto suite = createSuiteWithResolvedValueScenarios();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto modelVar = findVar(vars, "model");
        assertThat(modelVar.getResolvedValue()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("Should resolve template default at suite level")
    void shouldResolveTemplateDefaultAtSuiteLevel() {
        TestSuiteResponseDto suite = createSuiteWithResolvedValueScenarios();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto tempVar = findVar(vars, "temperature");
        assertThat(tempVar.getResolvedValue()).isEqualTo("0.7");
    }

    @Test
    @DisplayName("Should return null resolvedValue for data-field binding at suite level")
    void shouldReturnNullResolvedValueForDataFieldBindingAtSuiteLevel() {
        TestSuiteResponseDto suite = createSuiteWithResolvedValueScenarios();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto promptVar = findVar(vars, "prompt");
        assertThat(promptVar.getResolvedValue()).isNull();
    }

    @Test
    @DisplayName("Should return null resolvedValue for unbound variable without default at suite level")
    void shouldReturnNullForUnboundVariableWithoutDefaultAtSuiteLevel() {
        TestSuiteResponseDto suite = createSuiteWithResolvedValueScenarios();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto versionVar = findVar(vars, "version");
        assertThat(versionVar.getResolvedValue()).isNull();
    }

    @Test
    @DisplayName("Should resolve default for data-field binding with default at suite level")
    void shouldResolveDefaultForDataFieldBindingWithDefaultAtSuiteLevel() {
        TestSuiteResponseDto suite = createSuiteWithResolvedValueScenarios();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto fallbackVar = findVar(vars, "fallbackVar");
        // data-field binding at suite level → no test case data → falls back to default
        assertThat(fallbackVar.getResolvedValue()).isEqualTo("fallback");
    }

    // --- Type hint tests ---

    @Test
    @DisplayName("Should return declaredType and effectiveType for variable with type hint")
    void shouldReturnDeclaredAndEffectiveTypeForTypeHintVariable() {
        TestSuiteResponseDto suite = createSuiteWithTypeHints();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto docVar = findVar(vars, "doc");
        assertThat(docVar.getDeclaredType()).isEqualTo(SchemaFieldType.FILE);
        assertThat(docVar.getEffectiveType()).isEqualTo(SchemaFieldType.FILE);
    }

    @Test
    @DisplayName("Should return declaredType and effectiveType for variable with type hint and default")
    void shouldReturnDeclaredTypeWithDefault() {
        TestSuiteResponseDto suite = createSuiteWithTypeHints();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto tempVar = findVar(vars, "temperature");
        assertThat(tempVar.getDeclaredType()).isEqualTo(SchemaFieldType.NUMBER);
        assertThat(tempVar.getEffectiveType()).isEqualTo(SchemaFieldType.NUMBER);
        assertThat(tempVar.isHasDefault()).isTrue();
        assertThat(tempVar.getDefaultValue()).isEqualTo("0.7");
    }

    @Test
    @DisplayName("Should return null declaredType and STRING effectiveType for plain variable")
    void shouldReturnNullDeclaredTypeForPlainVariable() {
        TestSuiteResponseDto suite = createSuiteWithTypeHints();

        List<TemplateVariableDto> vars = getTemplateVariables(suite.getId());

        TemplateVariableDto plainVar = findVar(vars, "plain");
        assertThat(plainVar.getDeclaredType()).isNull();
        assertThat(plainVar.getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
    }

    private TestSuiteResponseDto createSuiteWithTypeHints() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "doc", "${{doc|file}}",
                                "temperature", "${{temperature|number:0.7}}",
                                "plain", "${{plain}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Type Hint Suite " + UUID.randomUUID())
                .description("Suite with type hints")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("col1")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(template)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<TemplateVariableDto> getTemplateVariables(UUID suiteId) {
        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static TemplateVariableDto findVar(List<TemplateVariableDto> vars, String name) {
        return vars.stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + name));
    }

    private TestSuiteResponseDto createSuiteWithResolvedValueScenarios() {
        // Template with multiple resolution scenarios:
        // - model: constant-value binding → resolvedValue = "gpt-4"
        // - prompt: data-field binding (no default) → resolvedValue = null at suite level
        // - temperature: no binding, has default "0.7" → resolvedValue = "0.7"
        // - version: no binding, no default → resolvedValue = null
        // - fallbackVar: data-field binding with default → resolvedValue = "fallback" at suite level
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "model", "${{model}}",
                                "prompt", "${{prompt}}",
                                "temperature", "${{temperature:0.7}}",
                                "version", "${{version}}",
                                "extra", "${{fallbackVar:fallback}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("ResolvedValue Suite " + UUID.randomUUID())
                .description("Suite for resolvedValue scenarios")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("promptField")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("extraField")
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
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("fallbackVar")
                                .dataField("extraField")
                                .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createSuiteWithTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "prompt", "${{prompt}}",
                                "temperature", "${{temperature:0.7}}"))
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Template Suite " + UUID.randomUUID())
                .description("Suite with template")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("promptField")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("expected")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .build())))
                .requestTemplate(template)
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("prompt")
                        .dataField("promptField")
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createSuiteWithoutTemplate() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("No Template Suite " + UUID.randomUUID())
                .description("Suite without template")
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
        return response.getBody();
    }

    private DeploymentReferenceDto buildDeploymentRef() {
        return DeploymentReferenceDto.builder()
                .id("deployment-1")
                .name("Deployment One")
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
