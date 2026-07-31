package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for MCP_TOOL test suite CRUD operations.
 * Covers task 13.4: create, update, type-specific fields, type immutability, filtering by suiteType.
 */
@DisplayName("MCP TestSuite CRUD Functional Tests")
public abstract class McpTestSuiteFunctionalTests extends AbstractMcpFunctionalTest {

    @Test
    @DisplayName("Should create MCP_TOOL suite with toolset deployment ref")
    void shouldCreateMcpToolSuiteWithToolset() {
        TestSuiteRequestDto request = buildMcpSuiteRequest("MCP Suite Toolset " + UUID.randomUUID(), "dial-toolset");

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuiteType()).isEqualTo(SuiteType.MCP_TOOL);
        assertThat(response.getBody().getMcpDeploymentRef()).isNotNull();
        assertThat(response.getBody().getMcpDeploymentRef().getId()).isEqualTo("my-toolset");
        assertThat(response.getBody().getMcpDeploymentRef().getType()).isEqualTo("dial-toolset");
        assertThat(response.getBody().getToolRef()).isNotNull();
        assertThat(response.getBody().getToolRef().getName()).isEqualTo("search");
        assertThat(response.getBody().getArgumentTemplate()).isNotNull();
        assertThat(response.getBody().getArgumentTemplate().getArguments()).containsKey("query");
        // Deployment-specific fields should be null for MCP suites
        assertThat(response.getBody().getDeploymentRef()).isNull();
        assertThat(response.getBody().getEndpointRef()).isNull();
    }

    @Test
    @DisplayName("Should create MCP_TOOL suite with application deployment ref")
    void shouldCreateMcpToolSuiteWithApplication() {
        TestSuiteRequestDto request = buildMcpSuiteRequest("MCP Suite App " + UUID.randomUUID(), "dial-application");

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuiteType()).isEqualTo(SuiteType.MCP_TOOL);
        assertThat(response.getBody().getMcpDeploymentRef().getType()).isEqualTo("dial-application");
    }

    @Test
    @DisplayName("Should get MCP suite by ID with all MCP fields")
    void shouldGetMcpSuiteById() {
        TestSuiteResponseDto created = createMcpSuite("MCP Suite Get " + UUID.randomUUID());

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuiteType()).isEqualTo(SuiteType.MCP_TOOL);
        assertThat(response.getBody().getMcpDeploymentRef()).isNotNull();
        assertThat(response.getBody().getToolRef()).isNotNull();
        assertThat(response.getBody().getArgumentTemplate()).isNotNull();
    }

    @Test
    @DisplayName("Should update MCP suite and return updated fields")
    void shouldUpdateMcpSuite() {
        TestSuiteResponseDto created = createMcpSuite("MCP Suite Update " + UUID.randomUUID());

        // Keep same schema/toolRef/argumentTemplate/bindings to avoid 202 revalidation
        TestSuiteRequestDto updateRequest = TestSuiteRequestDto.builder()
                .name(created.getName())
                .description("Updated MCP description")
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("updated-toolset")
                        .type("dial-toolset")
                        .build())
                .toolRef(created.getToolRef())
                .argumentTemplate(created.getArgumentTemplate())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("userQuery")
                        .dataField("userQuery")
                        .build()))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("userQuery")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + created.getVersion() + "\"");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TestSuiteResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDescription()).isEqualTo("Updated MCP description");
        assertThat(body.getMcpDeploymentRef().getId()).isEqualTo("updated-toolset");
    }

    @Test
    @DisplayName("Should return 400 when changing suite type from DEPLOYMENT to MCP_TOOL")
    void shouldRejectSuiteTypeChangeDeploymentToMcp() {
        // Create a DEPLOYMENT suite
        TestSuiteRequestDto deploymentRequest = TestSuiteRequestDto.builder()
                .name("Deployment Suite " + UUID.randomUUID())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(metaTestDataHelper
                        .createDataset("mcp-empty-" + UUID.randomUUID())
                        .getId())
                .build();
        ResponseEntity<TestSuiteResponseDto> createRes = restTemplate.postForEntity(
                apiUrl("/test-suites"), jsonEntity(deploymentRequest), TestSuiteResponseDto.class);
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto created = createRes.getBody();

        // Try to update with MCP_TOOL type
        TestSuiteRequestDto mcpUpdate = buildMcpSuiteRequest(created.getName(), "dial-toolset");
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + created.getVersion() + "\"");
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(mcpUpdate, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Suite type cannot be changed");
    }

    @Test
    @DisplayName("Should return 400 when changing suite type from MCP_TOOL to DEPLOYMENT")
    void shouldRejectSuiteTypeChangeMcpToDeployment() {
        TestSuiteResponseDto created = createMcpSuite("MCP Immutable " + UUID.randomUUID());

        TestSuiteRequestDto deploymentUpdate = TestSuiteRequestDto.builder()
                .name(created.getName())
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(metaTestDataHelper
                        .createDataset("mcp-empty-" + UUID.randomUUID())
                        .getId())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + created.getVersion() + "\"");
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(deploymentUpdate, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Suite type cannot be changed");
    }

    @Test
    @DisplayName("Should return 400 for MCP_TOOL suite without mcpDeploymentRef")
    void shouldReturn400ForMcpSuiteWithoutMcpDeploymentRef() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MCP No Ref " + UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .toolRef(ToolReferenceDto.builder()
                        .name("search")
                        .inputSchema(Map.of("type", "object"))
                        .build())
                .datasetId(metaTestDataHelper
                        .createDataset("mcp-empty-" + UUID.randomUUID())
                        .getId())
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("mcpDeploymentRef");
    }

    @Test
    @DisplayName("Should return 400 for MCP_TOOL suite without toolRef")
    void shouldReturn400ForMcpSuiteWithoutToolRef() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MCP No Tool " + UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("toolset-1")
                        .type("dial-toolset")
                        .build())
                .datasetId(metaTestDataHelper
                        .createDataset("mcp-empty-" + UUID.randomUUID())
                        .getId())
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("toolRef");
    }

    @Test
    @DisplayName("Should filter suites by suiteType")
    void shouldFilterBySuiteType() {
        createMcpSuite("MCP Filter Suite " + UUID.randomUUID());

        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=100&filter=suiteType:eq:MCP_TOOL"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isNotEmpty();
        assertThat(response.getBody().getContent())
                .allSatisfy(suite -> assertThat(suite.getSuiteType()).isEqualTo(SuiteType.MCP_TOOL));
    }

    @Test
    @DisplayName("Should delete MCP suite")
    void shouldDeleteMcpSuite() {
        TestSuiteResponseDto created = createMcpSuite("MCP Delete " + UUID.randomUUID());

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()), HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> getResponse =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Default suite type is DEPLOYMENT when suiteType is omitted")
    void defaultSuiteTypeIsDeployment() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Default Type Suite " + UUID.randomUUID())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(metaTestDataHelper
                        .createDataset("mcp-empty-" + UUID.randomUUID())
                        .getId())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }

    // --- Helpers ---

    private TestSuiteResponseDto createMcpSuite(String name) {
        return createMcpSuite(name, "dial-toolset");
    }
}
