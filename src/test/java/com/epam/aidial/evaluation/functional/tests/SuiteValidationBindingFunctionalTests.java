package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
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

@DisplayName("Suite validation — binding cross-validation and placeholder handling")
public abstract class SuiteValidationBindingFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("svb-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("DEPLOYMENT suite with FILE part placeholder and matching binding is valid")
    void deploymentSuiteWithFilePlaceholderAndBinding_isValid() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Suite-file-placeholder-" + UUID.randomUUID())
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/upload")
                        .requestBodySchema(
                                MultipartFormDataRequestBodySchemaDto.builder().build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/upload")
                        .body(MultipartFormDataRequestBodyDto.builder()
                                .content(List.of(FormPartDto.builder()
                                        .name("attachment")
                                        .type(FormPartType.FILE)
                                        .value("${{contract_file}}")
                                        .build()))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("contract_file")
                        .dataField("file_path")
                        .build()))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("file_path")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertSuiteConfigValid(response.getBody());
        List<ValidationWarningDto> filePartWarnings = response.getBody().getValidationWarnings() == null
                ? List.of()
                : response.getBody().getValidationWarnings().stream()
                        .filter(w -> w.getMessage() != null && w.getMessage().contains("FILE form part"))
                        .toList();
        assertThat(filePartWarnings).isEmpty();
    }

    @Test
    @DisplayName("MCP suite with required variable and no binding produces REQUIRED warning")
    void mcpSuiteWithMissingBinding_producesRequiredWarning() {
        TestSuiteRequestDto request = buildMcpSuiteRequest(
                Map.of("query", "${{query}}"),
                List.of(),
                List.of(FieldDefinitionDto.builder()
                        .name("question")
                        .type(SchemaFieldType.STRING)
                        .build()));

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        assertThat(response.getBody().getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.REQUIRED
                        && w.getFieldName() != null
                        && w.getFieldName().equals("query"));
    }

    @Test
    @DisplayName("MCP suite with |file binding and valid constant ref is valid")
    void mcpSuiteWithFileBinding_isValid() {
        TestSuiteRequestDto request = buildMcpSuiteRequest(
                Map.of("document", "${{doc|file}}"),
                List.of(InputBindingDto.builder()
                        .templateVariable("doc")
                        .constantValue("public/shared/input.csv")
                        .build()),
                List.of());

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertSuiteConfigValid(response.getBody());
        assertThat(configWarnings(response.getBody())).isEmpty();
    }

    @Test
    @DisplayName("MCP suite with null argumentTemplate produces ADDITIONAL warning and valid = false")
    void mcpSuiteWithNullArgumentTemplate_producesWarning() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MCP-null-argtemplate-" + UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("my-toolset")
                        .type("dial-toolset")
                        .name("My Toolset")
                        .build())
                .toolRef(ToolReferenceDto.builder()
                        .name("search")
                        .inputSchema(Map.of("type", "object"))
                        .build())
                .argumentTemplate(null)
                .datasetId(newDatasetWithSchema(List.of()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isFalse();
        assertThat(response.getBody().getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.ADDITIONAL
                        && w.getMessage() != null
                        && w.getMessage().contains("argumentTemplate"));
    }

    private TestSuiteRequestDto buildMcpSuiteRequest(
            Map<String, Object> arguments, List<InputBindingDto> bindings, List<FieldDefinitionDto> schema) {
        return TestSuiteRequestDto.builder()
                .name("MCP-binding-test-" + UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("my-toolset")
                        .type("dial-toolset")
                        .name("My Toolset")
                        .build())
                .toolRef(ToolReferenceDto.builder()
                        .name("search")
                        .inputSchema(Map.of("type", "object"))
                        .build())
                .argumentTemplate(
                        ArgumentTemplateDto.builder().arguments(arguments).build())
                .inputBindings(bindings)
                .datasetId(newDatasetWithSchema(schema))
                .build();
    }
}
