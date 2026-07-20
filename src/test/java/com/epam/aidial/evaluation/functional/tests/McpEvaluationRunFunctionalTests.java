package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for MCP evaluation run end-to-end (Task 13.7).
 * Creates MCP_TOOL suite, runs evaluation, verifies analytics results.
 */
@DisplayName("MCP Evaluation Run Functional Tests")
public abstract class McpEvaluationRunFunctionalTests extends AbstractMcpFunctionalTest {

    @Autowired
    private McpToolInvoker mcpToolInvoker;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @BeforeEach
    void resetMcpMocks() {
        reset(mcpToolInvoker);
    }

    @Test
    @DisplayName("Should complete MCP run and write results to analytics DB")
    void shouldCompleteMcpRunWithAnalyticsResults() {
        TestSuiteResponseDto suite = createMcpSuiteWithResponseColumn();
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "What is AI?"));
        createTestCase(suite.getId(), "TC2", Map.of("userQuery", "Explain ML"));

        CallToolResult callResult = new CallToolResult(
                List.of(TextContent.builder("{\"answer\": \"AI is artificial intelligence\"}")
                        .build()),
                false,
                null,
                null);
        when(mcpToolInvoker.callTool(eq("my-toolset"), eq("search"), any(), any(), any()))
                .thenReturn(callResult);

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "SUCCESS".equals(r.get("execution_status")));
        assertThat(results).allMatch(r -> r.get("request_body") != null);
        assertThat(results).allMatch(r -> r.get("response_body") != null);
        assertThat(results).allMatch(r -> r.get("response_status_code") == null);
    }

    @Test
    @DisplayName("Should mark test case as ERROR when MCP invocation fails")
    void shouldMarkTestCaseAsErrorOnMcpFailure() {
        TestSuiteResponseDto suite = createMcpSuite();
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "test query"));

        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                .thenThrow(
                        new McpInvocationException(502, "MCP_CONNECTION_ERROR", "Failed to connect to MCP endpoint"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("execution_status")).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("Should mark test case as TIMEOUT when MCP invocation times out")
    void shouldMarkTestCaseAsTimeoutOnMcpTimeout() {
        TestSuiteResponseDto suite = createMcpSuite();
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "test query"));

        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                .thenThrow(new McpInvocationException(504, "MCP_TIMEOUT", "MCP tool invocation timed out"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("execution_status")).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("Should mark test case as FAILED when MCP tool returns isError=true")
    void shouldMarkTestCaseAsFailedWhenMcpToolReturnsError() {
        TestSuiteResponseDto suite = createMcpSuite();
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "bad query"));

        CallToolResult errorResult = new CallToolResult(
                List.of(TextContent.builder("Tool execution failed: invalid input")
                        .build()),
                true,
                null,
                null);
        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any())).thenReturn(errorResult);

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("execution_status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Should extract response columns from MCP response")
    void shouldExtractResponseColumnsFromMcpResponse() {
        TestSuiteResponseDto suite = createMcpSuiteWithResponseColumn();
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "What is AI?"));

        CallToolResult callResult = new CallToolResult(
                List.of(TextContent.builder("{\"answer\": \"AI is artificial intelligence\"}")
                        .build()),
                false,
                null,
                null);
        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any())).thenReturn(callResult);

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        String extractedColumns = (String) results.get(0).get("extracted_columns");
        assertThat(extractedColumns).isNotNull();
        // The response column "first_text" with expression "content[0].text" should extract
        // the text content from the MCP response envelope
        assertThat(extractedColumns).contains("first_text");
        assertThat(extractedColumns).contains("AI is artificial intelligence");
    }

    @Test
    @DisplayName("Run creation on an MCP suite whose dataset has multiTurn rows is rejected with 409")
    void shouldRejectRunForMcpSuiteWithMultiTurnRows() {
        TestSuiteResponseDto suite = createMcpSuite();
        createMultiTurnTurn(suite.getId(), "conv / turn 0", UUID.randomUUID(), 0, Map.of("userQuery", "hi"));

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("multi-turn");
    }

    // --- Helper Methods ---

    private TestCaseResponseDto createMultiTurnTurn(
            UUID testSuiteId, String name, UUID multiTurnId, int turnIndex, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(testSuiteId);
        TestCaseRequestDto req = TestCaseRequestDto.builder()
                .testCaseName(name)
                .multiTurnId(multiTurnId)
                .turnIndex(turnIndex)
                .data(data)
                .build();
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID testSuiteId, int numberOfRuns, String name) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + testSuiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder()
                                .numberOfRuns(numberOfRuns)
                                .testRunName(name)
                                .build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), 15);
    }

    private TestSuiteResponseDto createMcpSuite() {
        return createMcpSuite("MCP Eval Suite " + UUID.randomUUID(), "dial-toolset");
    }

    private TestSuiteResponseDto createMcpSuiteWithResponseColumn() {
        TestSuiteRequestDto request = buildMcpSuiteRequest("MCP Eval Suite RC " + UUID.randomUUID(), "dial-toolset");
        request.setResponseColumns(List.of(ResponseColumnDefinitionDto.builder()
                .name("first_text")
                .expression("content[0].text")
                .type(SchemaFieldType.STRING)
                .build()));
        ResponseEntity<TestSuiteResponseDto> res =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }
}
