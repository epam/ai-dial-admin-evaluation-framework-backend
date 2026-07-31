package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableDto;
import com.epam.aidial.evaluation.service.domain.dto.TemplateVariableSource;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutWithVariablesRequestDto;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for MCP try-it-out (test case and variables modes).
 * Covers task 13.5.
 */
@DisplayName("MCP Try-It-Out Functional Tests")
public abstract class McpTryItOutFunctionalTests extends AbstractMcpFunctionalTest {

    @Autowired
    private McpToolInvoker mcpToolInvoker;

    @BeforeEach
    void resetMcpMock() {
        reset(mcpToolInvoker);
    }

    @Test
    @DisplayName("Should try-it-out with MCP test case and get resolved args + MCP response")
    void shouldTryItOutWithMcpTestCase() {
        TestSuiteResponseDto suite = createMcpSuiteWithTestCaseSchema();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC1", Map.of("userQuery", "What is AI?"));

        CallToolResult result = new CallToolResult(
                List.of(TextContent.builder("AI is artificial intelligence").build()), false, null, null);
        when(mcpToolInvoker.callTool(eq("my-toolset"), eq("search"), any(), any(), any()))
                .thenReturn(result);

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResolvedRequest()).isNotNull();
        assertThat(response.getBody().getResolvedRequest().getBody()).isNotNull();
        assertThat(response.getBody().getResponse()).isNotNull();
        assertThat(response.getBody().getResponse().getStatusCode()).isEqualTo(200);
        assertThat(response.getBody().getDurationMs()).isNotNull();
    }

    @Test
    @DisplayName("Should try-it-out with MCP variables and get resolved args + MCP response")
    void shouldTryItOutWithMcpVariables() {
        TestSuiteResponseDto suite = createMcpSuiteWithTestCaseSchema();

        CallToolResult result = new CallToolResult(
                List.of(TextContent.builder("Variable result").build()), false, null, null);
        when(mcpToolInvoker.callTool(eq("my-toolset"), eq("search"), any(), any(), any()))
                .thenReturn(result);

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("userQuery", "Hello from variables"))
                .build();

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"),
                jsonEntity(request),
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResponse()).isNotNull();
        assertThat(response.getBody().getResponse().getStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should return 502 when MCP tool invocation fails")
    void shouldReturn502ForMcpConnectionError() {
        TestSuiteResponseDto suite = createMcpSuiteWithTestCaseSchema();

        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                .thenThrow(
                        new McpInvocationException(502, "MCP_CONNECTION_ERROR", "Failed to connect to MCP endpoint"));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("userQuery", "test"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("Should return 504 when MCP tool invocation times out")
    void shouldReturn504ForMcpTimeout() {
        TestSuiteResponseDto suite = createMcpSuiteWithTestCaseSchema();

        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any()))
                .thenThrow(new McpInvocationException(504, "MCP_TIMEOUT", "MCP tool invocation timed out"));

        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("userQuery", "test"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/try-it-out"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("Should return 404 for non-existent MCP suite (test case path)")
    void shouldReturn404ForNonExistentMcpSuiteTestCase() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/test-cases/" + UUID.randomUUID() + "/try-it-out"),
                null,
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 for non-existent MCP suite (variables path)")
    void shouldReturn404ForNonExistentMcpSuiteVariables() {
        TryItOutWithVariablesRequestDto request = TryItOutWithVariablesRequestDto.builder()
                .variables(Map.of("userQuery", "test"))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/try-it-out"), jsonEntity(request), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- 13.6 Template variables for MCP suites ---

    @Test
    @DisplayName("Should return ARGUMENT-sourced template variables for MCP suite")
    void shouldReturnMcpSuiteTemplateVariables() {
        TestSuiteResponseDto suite = createMcpSuiteWithTestCaseSchema();

        ResponseEntity<List<TemplateVariableDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/template-variables"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        TemplateVariableDto var = response.getBody().get(0);
        assertThat(var.getName()).isEqualTo("userQuery");
        assertThat(var.getSources()).containsExactly(TemplateVariableSource.ARGUMENT);
        assertThat(var.getEffectiveType()).isEqualTo(SchemaFieldType.STRING);
        assertThat(var.getResolvedValue()).isNull();
    }

    // Note: per-test-case template-variables endpoint was removed in task group 11
    // (TemplateVariableService simplified to suite-scoped only).

    // --- Helpers ---

    private TestSuiteResponseDto createMcpSuiteWithTestCaseSchema() {
        return createMcpSuite("MCP TryItOut Suite " + UUID.randomUUID(), "dial-toolset");
    }
}
