package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.configuration.security.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapperImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests that verify token is accessible when DeploymentService
 * calls the unified getDeployments endpoint on DialCoreClient.
 */
@DisplayName("DeploymentService token propagation")
class DeploymentServiceTokenPropagationTest {

    private DialCoreClient dialCoreClient;
    private DeploymentMapper deploymentMapper;
    private SchemaRouteExtractor schemaRouteExtractor;
    private DeploymentService deploymentService;

    private final AtomicReference<String> capturedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        dialCoreClient = mock(DialCoreClient.class);
        deploymentMapper = new DeploymentMapperImpl();
        schemaRouteExtractor = mock(SchemaRouteExtractor.class);
        McpToolInvoker mcpToolInvoker = mock(McpToolInvoker.class);
        ObjectMapper objectMapper = new ObjectMapper();
        deploymentService = new DeploymentService(
                dialCoreClient, deploymentMapper, schemaRouteExtractor, mcpToolInvoker, objectMapper);
        capturedToken.set(null);
    }

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("getAllDeployments propagates token to getDeployments call")
    void getAllDeploymentsPropagatesTokenToGetDeploymentsCall() {
        // Given: token is set in the current (request) thread
        String expectedToken = "test-jwt-token-12345";
        AuthorizationTokenHolder.setToken(expectedToken);

        // Mock client to capture what token is visible during the call
        when(dialCoreClient.getDeployments(null)).thenAnswer(invocation -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
            return List.of();
        });

        // When: getAllDeployments is called
        List<DeploymentInfoDto> result = deploymentService.getAllDeployments();

        // Then: the call should have seen the token
        assertThat(result).isEmpty();
        assertThat(capturedToken.get())
                .as("The getDeployments call should see the token from the request thread")
                .isEqualTo(expectedToken);
    }

    @Test
    @DisplayName("getAllDeployments works when no token is set")
    void getAllDeploymentsWorksWithoutToken() {
        // Given: no token is set
        AuthorizationTokenHolder.clearToken();

        when(dialCoreClient.getDeployments(null)).thenAnswer(invocation -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
            return List.of();
        });

        // When
        List<DeploymentInfoDto> result = deploymentService.getAllDeployments();

        // Then: should complete without error, token is null
        assertThat(result).isEmpty();
        assertThat(capturedToken.get())
                .as("The getDeployments call should see null token")
                .isNull();
    }
}
