package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.functional.support.CallerIdentityProbeTools;
import com.epam.aidial.evaluation.functional.support.McpFunctionalTestSupport;
import com.epam.aidial.evaluation.functional.support.StaticJwtTestConfiguration;
import com.epam.aidial.evaluation.mcp.constants.McpToolNames;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * MCP server security functional tests (oidc + Api-Key parity), mirroring the property set of
 * {@code ApiKeyAuthenticationFunctionalTests}: raw unauthenticated/malformed-token 401s, and the
 * D-F2 caller identity probe for both a JWT bearer caller and an Api-Key caller.
 *
 * <p>{@code @Import(...)} is declared on the concrete nested registration in
 * {@code PostgresFunctionalTests}, not here; see {@code McpServerFoundationFunctionalTests}.
 */
@DisplayName("MCP server security (security mode: oidc, api-key enabled)")
public abstract class McpServerSecurityFunctionalTests extends BaseFunctionalTest {

    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint}")
    private String mcpEndpoint;

    @MockitoBean
    private CoreApiKeyIntrospector coreApiKeyIntrospector;

    @Autowired
    private DialCoreClient dialCoreClient;

    private McpSyncClient client;

    @BeforeEach
    void resetMocks() {
        reset(coreApiKeyIntrospector);
        reset(dialCoreClient);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
    }

    private McpFunctionalTestSupport support() {
        return new McpFunctionalTestSupport(baseUrl(), mcpEndpoint);
    }

    @Test
    @DisplayName("raw POST without credentials is rejected with 401")
    void rawPostWithoutCredentialsIsRejected() {
        ResponseEntity<String> response =
                support().rawPost(restTemplate, Map.of(), McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("raw POST with a malformed bearer token is rejected with 401")
    void rawPostWithMalformedBearerIsRejected() {
        ResponseEntity<String> response = support()
                .rawPost(
                        restTemplate,
                        Map.of(HttpHeaders.AUTHORIZATION, "Bearer malformed.token.value"),
                        McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("raw POST initialize with a JWT lacking an allowed role is rejected with the same status "
            + "the REST API returns for that JWT (403 Forbidden)")
    void rawPostWithRoleLackingJwtIsRejectedWithForbidden() {
        ResponseEntity<String> response = support()
                .rawPost(
                        restTemplate,
                        Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + StaticJwtTestConfiguration.BOB_TOKEN),
                        McpFunctionalTestSupport.initializeRequestBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a JWT bearer caller (alice) is visible to tool code via probe_caller_identity")
    void jwtBearerCallerIsVisibleToToolCode() {
        McpFunctionalTestSupport support = support();
        client = support.client(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + StaticJwtTestConfiguration.ALICE_TOKEN));
        client.initialize();

        JsonNode probe = support.readJson(callProbe(support));

        assertThat(probe.get("createdBy").asString()).isEqualTo("alice");
        assertThat(probe.get("credentialKind").asString()).isEqualTo("BEARER");
        assertThat(probe.get("authenticationPresent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an Api-Key caller is attributed to the introspected project principal with an API-key credential")
    void apiKeyCallerIsAttributedToProjectPrincipalWithApiKeyCredential() {
        when(coreApiKeyIntrospector.introspect("valid-key"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));

        McpFunctionalTestSupport support = support();
        client = support.client(Map.of(CallerCredential.API_KEY_HEADER, "valid-key"));
        client.initialize();

        JsonNode probe = support.readJson(callProbe(support));

        assertThat(probe.get("createdBy").asString()).isEqualTo("my-project");
        assertThat(probe.get("credentialKind").asString()).isEqualTo("API_KEY");
        assertThat(probe.get("authenticationPresent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("list_deployments succeeds for both a JWT bearer caller and an Api-Key caller, "
            + "with probe_caller_identity reporting the respective identity for each")
    void listDeploymentsAndProbeSucceedWithParityForBothCallerTypes() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(DialCoreModelDto.builder()
                        .id("m1")
                        .displayName("Model 1")
                        .build()));

        McpFunctionalTestSupport support = support();

        McpSyncClient aliceClient =
                support.client(Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + StaticJwtTestConfiguration.ALICE_TOKEN));
        try {
            aliceClient.initialize();
            JsonNode aliceDeployments =
                    support.readJson(support.callTool(aliceClient, McpToolNames.LIST_DEPLOYMENTS, Map.of()));
            assertThat(aliceDeployments.get("total").asInt()).isEqualTo(1);
            JsonNode aliceProbe =
                    support.readJson(support.callTool(aliceClient, CallerIdentityProbeTools.PROBE_TOOL_NAME, Map.of()));
            assertThat(aliceProbe.get("createdBy").asString()).isEqualTo("alice");
            assertThat(aliceProbe.get("credentialKind").asString()).isEqualTo("BEARER");
        } finally {
            aliceClient.closeGracefully();
        }

        when(coreApiKeyIntrospector.introspect("valid-key"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));

        McpSyncClient apiKeyClient = support.client(Map.of(CallerCredential.API_KEY_HEADER, "valid-key"));
        try {
            apiKeyClient.initialize();
            JsonNode apiKeyDeployments =
                    support.readJson(support.callTool(apiKeyClient, McpToolNames.LIST_DEPLOYMENTS, Map.of()));
            assertThat(apiKeyDeployments.get("total").asInt()).isEqualTo(1);
            JsonNode apiKeyProbe = support.readJson(
                    support.callTool(apiKeyClient, CallerIdentityProbeTools.PROBE_TOOL_NAME, Map.of()));
            assertThat(apiKeyProbe.get("createdBy").asString()).isEqualTo("my-project");
            assertThat(apiKeyProbe.get("credentialKind").asString()).isEqualTo("API_KEY");
        } finally {
            apiKeyClient.closeGracefully();
        }
    }

    private McpSchema.CallToolResult callProbe(McpFunctionalTestSupport support) {
        return support.callTool(client, CallerIdentityProbeTools.PROBE_TOOL_NAME, Map.of());
    }
}
