package com.epam.aidial.evaluation.functional.support;

import com.epam.aidial.evaluation.mcp.support.McpCallerContext;
import com.epam.aidial.evaluation.service.domain.AuthorResolver;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.test.context.TestConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only MCP tool that reports the caller identity as seen by tool code, verifying the
 * request-thread caller model (design D-F2) before any production tool exists. Declared as a
 * {@code @TestConfiguration} rather than a {@code @Component} so it is never picked up by normal
 * application component scanning; once a test class {@code @Import}s it, the configuration class
 * itself becomes a bean and Spring AI's annotation scanner discovers its {@code @McpTool} method
 * on it, exactly as it would on any other bean.
 */
@TestConfiguration
@RequiredArgsConstructor
public class CallerIdentityProbeTools {

    public static final String PROBE_TOOL_NAME = "probe_caller_identity";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final McpCallerContext callerContext;
    private final AuthorResolver authorResolver;

    @McpTool(
            name = PROBE_TOOL_NAME,
            description = "Test-only probe returning the caller identity as seen by tool code.")
    public McpSchema.CallToolResult probeCallerIdentity() {
        Map<String, Object> payload = Map.of(
                "threadName",
                Thread.currentThread().getName(),
                "authenticationPresent",
                callerContext.authentication() != null,
                "createdBy",
                authorResolver.getCreatedBy(callerContext.jwt()),
                "bearerTokenPresent",
                callerContext.bearerToken() != null);
        return McpSchema.CallToolResult.builder()
                .addTextContent(JSON_MAPPER.writeValueAsString(payload))
                .build();
    }
}
