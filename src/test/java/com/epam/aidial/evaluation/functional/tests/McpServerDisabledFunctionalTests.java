package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MCP server disabled functional tests (security mode: none, {@code spring.ai.mcp.server.enabled=false}):
 * with no autoconfigured router function registered, a POST to the configured MCP endpoint falls
 * through to plain Spring MVC and 404s, and no {@link WebMvcStreamableServerTransportProvider} bean
 * exists in the context (see design D-F3, D-F9).
 */
@DisplayName("MCP server disabled (security mode: none)")
public abstract class McpServerDisabledFunctionalTests extends BaseFunctionalTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    @Autowired
    private ApplicationContext applicationContext;

    private McpFunctionalTestSupport support() {
        return new McpFunctionalTestSupport(baseUrl(), mcpEndpoint);
    }

    @Test
    @DisplayName("raw initialize POST to the MCP endpoint returns 404 when the MCP server is disabled")
    void rawInitializePostReturns404WhenDisabled() {
        ResponseEntity<String> response =
                support().rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("no Streamable HTTP transport-provider bean exists in the context when the MCP server is disabled")
    void transportProviderBeanIsAbsentWhenDisabled() {
        assertThat(applicationContext.getBeansOfType(WebMvcStreamableServerTransportProvider.class))
                .isEmpty();
    }
}
