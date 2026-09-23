package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import com.epam.aidial.evaluation.functional.support.StaticJwtTestConfiguration;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * MCP server on a non-default endpoint (security mode: oidc). The security matcher must cover the
 * exact path the Spring AI transport registers, including a trailing slash, so an operator override
 * of {@code spring.ai.mcp.server.streamable-http.mcp-endpoint} can never leave the transport route
 * outside the authenticated matcher (which would fall through to {@code denyAll}).
 */
@DisplayName("MCP server on a non-default endpoint (security mode: oidc)")
public abstract class McpServerCustomEndpointOidcFunctionalTests extends BaseFunctionalTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    private McpSyncClient client;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    private McpFunctionalTestSupport support() {
        return new McpFunctionalTestSupport(baseUrl(), mcpEndpoint);
    }

    @Test
    @DisplayName("the configured endpoint ends with a slash, so the test really exercises the non-normalised form")
    void configuredEndpointIsNonDefaultWithTrailingSlash() {
        assertThat(mcpEndpoint).isNotEqualTo("/mcp").endsWith("/");
    }

    @Test
    @DisplayName("initialize with a valid bearer token succeeds on the configured trailing-slash endpoint")
    void initializeWithValidBearerSucceedsOnConfiguredEndpoint() {
        client =
                support().client(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + StaticJwtTestConfiguration.ALICE_TOKEN));

        var result = client.initialize();

        assertThat(result.serverInfo().name()).isNotBlank();
    }

    @Test
    @DisplayName("raw initialize POST without credentials returns 401 on the configured endpoint")
    void rawInitializePostWithoutCredentialsReturns401() {
        ResponseEntity<String> response =
                support().rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
