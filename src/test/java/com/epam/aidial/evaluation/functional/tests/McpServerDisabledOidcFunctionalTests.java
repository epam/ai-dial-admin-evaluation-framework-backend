package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import com.epam.aidial.evaluation.functional.support.StaticJwtTestConfiguration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MCP server disabled functional tests (security mode: oidc, {@code spring.ai.mcp.server.enabled=false}):
 * the D-F3 security matcher still authenticates the MCP endpoint even when no MCP router function
 * is registered, so an unauthenticated caller is rejected before ever reaching the (missing) route,
 * and an authenticated caller reaches the route and gets a plain 404 (see design D-F3, D-F9).
 */
@DisplayName("MCP server disabled (security mode: oidc)")
public abstract class McpServerDisabledOidcFunctionalTests extends BaseFunctionalTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    private McpFunctionalTestSupport support() {
        return new McpFunctionalTestSupport(baseUrl(), mcpEndpoint);
    }

    @Test
    @DisplayName("raw initialize POST with a valid bearer token returns 404 when the MCP server is disabled")
    void rawInitializePostWithValidBearerReturns404WhenDisabled() {
        ResponseEntity<String> response = support()
                .rawPost(
                        restTemplate,
                        Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + StaticJwtTestConfiguration.ALICE_TOKEN),
                        McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("raw initialize POST without credentials returns 401 when the MCP server is disabled")
    void rawInitializePostWithoutCredentialsReturns401WhenDisabled() {
        ResponseEntity<String> response =
                support().rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
