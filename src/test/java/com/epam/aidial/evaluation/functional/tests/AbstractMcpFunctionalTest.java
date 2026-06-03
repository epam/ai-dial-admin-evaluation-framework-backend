package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ToolReferenceDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Shared base class for MCP functional tests — provides common fixture helpers
 * (createTestCase, awaitRunTerminal, MCP suite creation) to avoid duplication
 * across McpEvaluationRunFunctionalTests, McpTryItOutFunctionalTests, etc.
 */
public abstract class AbstractMcpFunctionalTest extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    protected UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("mcp-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
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

    protected TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    protected TestSuiteResponseDto createMcpSuite(String name, String deploymentType) {
        TestSuiteRequestDto request = buildMcpSuiteRequest(name, deploymentType);
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    protected TestSuiteRequestDto buildMcpSuiteRequest(String name, String deploymentType) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("MCP test suite")
                .suiteType(SuiteType.MCP_TOOL)
                .mcpDeploymentRef(McpDeploymentReferenceDto.builder()
                        .id("my-toolset")
                        .type(deploymentType)
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
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("userQuery")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .build();
    }
}
