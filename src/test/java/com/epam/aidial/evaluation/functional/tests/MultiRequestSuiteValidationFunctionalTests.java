package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Functional coverage for section 2 (write-time and soft validation) of the {@code
 * add-multi-request-suite} change: the four request-chain-specific HTTP 400s enforced by {@code
 * TestSuiteRequestValidator} / Bean Validation, plus a successful multi-request chain round-trip
 * through the real controller pipeline.
 */
@DisplayName("Multi-request suite validation — write-time 400s and chain round-trip")
public abstract class MultiRequestSuiteValidationFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("mrs-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("400 when a response column name is duplicated across request #0 and an additional request")
    void shouldReturn400_whenResponseColumnNameDuplicatedAcrossChain() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-duplicate-column-" + UUID.randomUUID());
        request.setResponseColumns(List.of(column("answer", "choices[0].message.content")));
        request.setAdditionalRequests(
                List.of(chainRequest("second", "/v1/second", List.of(column("answer", "usage.total_tokens")))));

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("duplicate column name").contains("answer");
    }

    @Test
    @DisplayName("400 when the chain-wide response column union exceeds MAX_RESPONSE_COLUMNS")
    void shouldReturn400_whenResponseColumnUnionExceedsCap() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-union-cap-" + UUID.randomUUID());
        request.setResponseColumns(namedColumns("s", 30));
        request.setAdditionalRequests(List.of(chainRequest("second", "/v1/second", namedColumns("a", 21))));

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("51").contains("exceeds maximum of 50");
    }

    @Test
    @DisplayName("400 when additionalRequests exceeds MAX_ADDITIONAL_REQUESTS (chain length cap)")
    void shouldReturn400_whenAdditionalRequestsExceedsChainLengthCap() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-length-cap-" + UUID.randomUUID());
        List<RequestDefinitionDto> elevenRequests = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            elevenRequests.add(chainRequest("r" + i, "/v1/r" + i, List.of()));
        }
        request.setAdditionalRequests(elevenRequests);

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Ten additionalRequests (at the chain length cap) are accepted")
    void shouldAccept_whenAdditionalRequestsAtChainLengthCap() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-length-cap-boundary-" + UUID.randomUUID());
        List<RequestDefinitionDto> tenRequests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenRequests.add(chainRequest("r" + i, "/v1/r" + i, List.of()));
        }
        request.setAdditionalRequests(tenRequests);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAdditionalRequests()).hasSize(10);
    }

    @Test
    @DisplayName("400 when additionalRequests contains a null element")
    void shouldReturn400_whenAdditionalRequestsContainsNullElement() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-null-element-" + UUID.randomUUID());
        List<RequestDefinitionDto> withNull = new ArrayList<>();
        withNull.add(null);
        request.setAdditionalRequests(withNull);

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests[0]").contains("must not be null");
    }

    @Test
    @DisplayName("400 when an MCP_TOOL suite carries a non-empty additionalRequests")
    void shouldReturn400_whenMcpToolSuiteHasNonEmptyAdditionalRequests() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MCP-chain-guard-" + UUID.randomUUID())
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("toolset")
                        .type("dial-toolset")
                        .name("Toolset")
                        .build())
                .toolRef(ToolReferenceDto.builder()
                        .name("tool")
                        .inputSchema(Map.of("type", "object"))
                        .build())
                .additionalRequests(List.of(chainRequest("second", "/v1/second", List.of())))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests").contains("MCP_TOOL");
    }

    @Test
    @DisplayName("A successful two-request chain persists and round-trips through create and update")
    void shouldPersistAndUpdateSuccessfulChainRoundTrip() {
        TestSuiteRequestDto request = baseDeploymentRequest("Chain-round-trip-" + UUID.randomUUID());
        request.setRequestName("configure");
        request.setResponseColumns(List.of(column("configId", "usage.total_tokens")));
        request.setAdditionalRequests(
                List.of(chainRequest("ask", "/v1/second", List.of(column("answer", "choices[0].message.content")))));

        ResponseEntity<TestSuiteResponseDto> createResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().getRequestName()).isEqualTo("configure");
        assertThat(createResponse.getBody().getAdditionalRequests()).hasSize(1);
        assertThat(createResponse.getBody().getAdditionalRequests().get(0).getName())
                .isEqualTo("ask");
        assertThat(createResponse.getBody().getAdditionalRequests().get(0).getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("answer");

        // Update: replace the chain with a different single additional request (unique column names)
        TestSuiteRequestDto updateRequest =
                baseDeploymentRequest(createResponse.getBody().getName());
        updateRequest.setRequestName("configure-v2");
        updateRequest.setResponseColumns(List.of(column("configId", "usage.total_tokens")));
        updateRequest.setAdditionalRequests(List.of(
                chainRequest("ask-v2", "/v1/second-v2", List.of(column("reply", "choices[0].message.content")))));
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + createResponse.getBody().getVersion() + "\"");

        ResponseEntity<TestSuiteResponseDto> updateResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + createResponse.getBody().getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().getRequestName()).isEqualTo("configure-v2");
        assertThat(updateResponse.getBody().getAdditionalRequests()).hasSize(1);
        assertThat(updateResponse.getBody().getAdditionalRequests().get(0).getName())
                .isEqualTo("ask-v2");
        assertThat(updateResponse.getBody().getAdditionalRequests().get(0).getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("reply");

        // GET confirms persistence
        ResponseEntity<TestSuiteResponseDto> getResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + createResponse.getBody().getId()), TestSuiteResponseDto.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getRequestName()).isEqualTo("configure-v2");
        assertThat(getResponse.getBody().getAdditionalRequests()).hasSize(1);
    }

    private TestSuiteRequestDto baseDeploymentRequest(String name) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("Multi-request suite validation fixture")
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();
    }

    private EndpointContractDto buildEndpointContract(String relativeUrlPattern) {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern(relativeUrlPattern)
                .build();
    }

    private RequestDefinitionDto chainRequest(
            String name, String urlTemplate, List<ResponseColumnDefinitionDto> columns) {
        return RequestDefinitionDto.builder()
                .name(name)
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate(urlTemplate).build())
                .responseColumns(columns)
                .build();
    }

    private ResponseColumnDefinitionDto column(String name, String expression) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression(expression)
                .build();
    }

    private List<ResponseColumnDefinitionDto> namedColumns(String prefix, int count) {
        List<ResponseColumnDefinitionDto> columns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            columns.add(column(prefix + i, "usage.total_tokens"));
        }
        return columns;
    }
}
