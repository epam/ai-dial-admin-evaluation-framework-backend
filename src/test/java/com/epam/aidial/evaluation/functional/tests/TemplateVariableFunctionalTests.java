package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableSource;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        } catch (JacksonException e) {
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
    @DisplayName("jsonataContent-authored placeholder bound in inputBindings is valid, warning-free, and reported "
            + "as a BODY-sourced template variable")
    void jsonataContentBoundVariableIsValidWithNoWarningsAndReportedAsBodySourced() {
        TestSuiteResponseDto suite = createSuiteWithJsonataContentTemplate();

        assertThat(suite.isValid()).isTrue();
        List<ValidationWarningDto> warnings =
                suite.getValidationWarnings() != null ? suite.getValidationWarnings() : List.of();
        assertThat(warnings)
                .noneMatch(w -> "prompt".equals(w.getFieldName())
                        && (w.getCode() == ValidationWarningCode.REQUIRED
                                || w.getCode() == ValidationWarningCode.ADDITIONAL));

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .anyMatch(v -> "prompt".equals(v.getName())
                        && v.getSources().contains(TemplateVariableSource.BODY)
                        && v.getSources().size() == 1);
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

    // --- Test-case-level template variable tests ---

    @Test
    @DisplayName("Should resolve data-field binding from test case data at test-case level")
    void shouldResolveDataFieldBindingFromTestCaseData() {
        SuiteTestCase fixture = createHttpSuiteWithTestCase("{\"promptField\":\"Hello there\"}");

        List<TemplateVariableDto> vars = getTestCaseTemplateVariables(fixture.suiteId(), fixture.testCaseId());

        assertThat(findVar(vars, "prompt").getResolvedValue()).isEqualTo("Hello there");
    }

    @Test
    @DisplayName("Should resolve constant-value binding at test-case level")
    void shouldResolveConstantValueBindingAtTestCaseLevel() {
        SuiteTestCase fixture = createHttpSuiteWithTestCase("{\"promptField\":\"x\"}");

        List<TemplateVariableDto> vars = getTestCaseTemplateVariables(fixture.suiteId(), fixture.testCaseId());

        assertThat(findVar(vars, "model").getResolvedValue()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("Should fall back to template default when data field is missing at test-case level")
    void shouldFallBackToTemplateDefaultWhenDataFieldMissing() {
        // data omits extraField → fallbackVar (bound to extraField) falls back to the template default "fallback"
        SuiteTestCase fixture = createHttpSuiteWithTestCase("{\"promptField\":\"x\"}");

        List<TemplateVariableDto> vars = getTestCaseTemplateVariables(fixture.suiteId(), fixture.testCaseId());

        assertThat(findVar(vars, "fallbackVar").getResolvedValue()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("Should preserve typed (Number/Boolean) resolvedValue from test case data at test-case level")
    void shouldPreserveTypedResolvedValueFromTestCaseData() {
        // Unquoted JSON literals → data holds a Number and a Boolean (not strings). resolvedValue must
        // preserve the original type rather than stringify it. prompt is bound to promptField (the Number),
        // fallbackVar is bound to extraField (the Boolean).
        SuiteTestCase fixture = createHttpSuiteWithTestCase("{\"promptField\":0.7,\"extraField\":true}");

        List<TemplateVariableDto> vars = getTestCaseTemplateVariables(fixture.suiteId(), fixture.testCaseId());

        assertThat(findVar(vars, "prompt").getResolvedValue())
                .isInstanceOf(Number.class)
                .satisfies(v -> assertThat(((Number) v).doubleValue()).isEqualTo(0.7));
        assertThat(findVar(vars, "fallbackVar").getResolvedValue()).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test suite at test-case level")
    void shouldReturn404ForNonExistentSuiteAtTestCaseLevel() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/test-cases/" + UUID.randomUUID()
                        + "/template-variables"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test case")
    void shouldReturn404ForNonExistentTestCase() {
        SuiteTestCase fixture = createHttpSuiteWithTestCase("{\"promptField\":\"x\"}");

        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + fixture.suiteId() + "/test-cases/" + UUID.randomUUID()
                        + "/template-variables"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 (not 500) for an unbound suite at test-case level")
    void shouldReturn404ForUnboundSuiteAtTestCaseLevel() {
        // Suite with datasetId == null owns no test cases — the endpoint must 404, not NPE→500.
        TestSuite unbound = metaTestDataHelper.createTestSuite("Unbound TV Suite " + UUID.randomUUID(), null);

        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + unbound.getId() + "/test-cases/" + UUID.randomUUID() + "/template-variables"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should resolve MCP test-case template variables from argument template and data")
    void shouldResolveMcpTestCaseTemplateVariables() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("userQuery")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MCP TV Suite " + UUID.randomUUID())
                .description("MCP suite for test-case template variables")
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("my-toolset")
                        .type("dial-toolset")
                        .name("My Toolset")
                        .build())
                .toolRef(ToolReferenceDto.builder()
                        .name("search")
                        .description("Search tool")
                        .inputSchema(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))
                        .build())
                .argumentTemplate(ArgumentTemplateDto.builder()
                        .arguments(Map.of("query", "${{userQuery}}"))
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("userQuery")
                        .dataField("userQuery")
                        .build()))
                .datasetId(datasetId)
                .build();
        ResponseEntity<TestSuiteResponseDto> createResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID suiteId = createResponse.getBody().getId();
        UUID testCaseId = metaTestDataHelper.seedTestCaseInDataset(
                datasetId, "mcp-tc-" + UUID.randomUUID(), "{\"userQuery\":\"What is AI?\"}");

        List<TemplateVariableDto> vars = getTestCaseTemplateVariables(suiteId, testCaseId);

        TemplateVariableDto userQuery = findVar(vars, "userQuery");
        assertThat(userQuery.getResolvedValue()).isEqualTo("What is AI?");
        assertThat(userQuery.getSources()).contains(TemplateVariableSource.ARGUMENT);
    }

    /** Identifiers for a suite plus a seeded test case in its dataset. */
    private record SuiteTestCase(UUID suiteId, UUID testCaseId) {}

    /**
     * Creates an HTTP suite (constant-value, data-field, and default-fallback bindings) bound to a fresh
     * dataset, seeds a test case with the supplied {@code dataJson}, and returns both ids. {@code dataJson}
     * is a raw JSON String; use unquoted JSON literals for numeric/boolean fields to exercise typed-value
     * resolution.
     */
    private SuiteTestCase createHttpSuiteWithTestCase(String dataJson) {
        UUID datasetId = newDatasetWithSchema(List.of(
                FieldDefinitionDto.builder()
                        .name("promptField")
                        .type(SchemaFieldType.STRING)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("extraField")
                        .type(SchemaFieldType.STRING)
                        .build()));
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of(
                                "model", "${{model}}",
                                "prompt", "${{prompt}}",
                                "extra", "${{fallbackVar:fallback}}"))
                        .build())
                .build();
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("TC TemplateVar Suite " + UUID.randomUUID())
                .description("Suite for test-case template variables")
                .deploymentRef(buildDeploymentRef())
                .endpointRef(buildEndpoint())
                .datasetId(datasetId)
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
        UUID suiteId = response.getBody().getId();
        UUID testCaseId = metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-" + UUID.randomUUID(), dataJson);
        return new SuiteTestCase(suiteId, testCaseId);
    }

    private List<TemplateVariableDto> getTestCaseTemplateVariables(UUID suiteId, UUID testCaseId) {
        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId + "/test-cases/" + testCaseId + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
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

    private TestSuiteResponseDto createSuiteWithJsonataContentTemplate() {
        RequestTemplateDto template = RequestTemplateDto.builder()
                .urlTemplate("/v1/chat")
                .body(JsonRequestBodyDto.builder()
                        .jsonataContent("{\"prompt\": \"${{prompt}}\"}")
                        .build())
                .build();

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("jsonataContent Template Suite " + UUID.randomUUID())
                .description("Suite with a jsonataContent-authored body")
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
