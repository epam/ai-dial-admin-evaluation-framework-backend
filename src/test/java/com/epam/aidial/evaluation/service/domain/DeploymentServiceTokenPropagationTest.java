package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapperImpl;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests that verify token is accessible when DeploymentService
 * calls the unified getDeployments endpoint on DialCoreClient.
 */
@DisplayName("DeploymentService token propagation")
class DeploymentServiceTokenPropagationTest {

    private static final String DEPLOYMENT_ID = "some-deployment";

    /** Sentinel for "this leg saw no token" — a ConcurrentHashMap cannot hold a null value. */
    private static final String NO_TOKEN = "<none>";

    private DialCoreClient dialCoreClient;
    private DeploymentMapper deploymentMapper;
    private SchemaRouteExtractor schemaRouteExtractor;
    private DeploymentService deploymentService;

    private final AtomicReference<String> capturedToken = new AtomicReference<>();

    /** Token observed by each parallel by-ID probe leg, keyed by deployment type value. */
    private final Map<String, String> probeTokens = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        dialCoreClient = mock(DialCoreClient.class);
        deploymentMapper = new DeploymentMapperImpl();
        schemaRouteExtractor = mock(SchemaRouteExtractor.class);
        McpToolInvoker mcpToolInvoker = mock(McpToolInvoker.class);
        ObjectMapper objectMapper = new ObjectMapper();
        deploymentService = new DeploymentService(
                dialCoreClient,
                deploymentMapper,
                schemaRouteExtractor,
                mcpToolInvoker,
                objectMapper,
                new DeploymentProbeCollapser());
        capturedToken.set(null);
        probeTokens.clear();
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

    @Test
    @DisplayName("getDeployment by ID propagates the token to all three parallel probe legs")
    void getDeploymentByIdPropagatesTokenToEveryProbe() {
        // Given: token is set in the current (request) thread, and only the model probe resolves
        String expectedToken = "test-jwt-token-12345";
        AuthorizationTokenHolder.setToken(expectedToken);
        stubProbesWithModelHit();

        // When
        DeploymentInfoDto result = deploymentService.getDeployment(DEPLOYMENT_ID);

        // Then: every leg ran on its own thread and saw the request thread's token
        assertThat(result).isInstanceOf(DialModelInfoDto.class);
        assertThat(probeTokens)
                .as("all three probe legs should see the token captured on the request thread")
                .containsOnly(
                        entry("dial-model", expectedToken),
                        entry("dial-application", expectedToken),
                        entry("dial-toolset", expectedToken));
    }

    @Test
    @DisplayName("getDeployment by ID works when no token is set")
    void getDeploymentByIdWorksWithoutToken() {
        // Given: no token is set
        AuthorizationTokenHolder.clearToken();
        stubProbesWithModelHit();

        // When
        DeploymentInfoDto result = deploymentService.getDeployment(DEPLOYMENT_ID);

        // Then: the lookup still resolves and every leg simply saw no token
        assertThat(result).isInstanceOf(DialModelInfoDto.class);
        assertThat(probeTokens)
                .containsOnly(
                        entry("dial-model", NO_TOKEN),
                        entry("dial-application", NO_TOKEN),
                        entry("dial-toolset", NO_TOKEN));
    }

    @Test
    @DisplayName("getDeployment by ID does not resolve routes for a losing application probe")
    void getDeploymentByIdSkipsRouteResolutionForLosingApplicationProbe() {
        // Given: both the model and the application probes resolve, so the model wins on precedence
        when(dialCoreClient.getModel(DEPLOYMENT_ID))
                .thenReturn(DialCoreModelDto.builder().id(DEPLOYMENT_ID).build());
        when(dialCoreClient.getApplication(DEPLOYMENT_ID))
                .thenReturn(DialCoreApplicationDto.builder().id(DEPLOYMENT_ID).build());
        when(dialCoreClient.getToolset(DEPLOYMENT_ID)).thenThrow(notFound());

        // When
        DeploymentInfoDto result = deploymentService.getDeployment(DEPLOYMENT_ID);

        // Then: the discarded application payload never reaches route resolution
        assertThat(result).isInstanceOf(DialModelInfoDto.class);
        verify(schemaRouteExtractor, never()).resolveRoutes(any());
    }

    @Test
    @DisplayName("getDeployment by ID resolves routes for a winning application probe")
    void getDeploymentByIdResolvesRoutesForWinningApplicationProbe() {
        // Given: only the application probe resolves
        DialCoreApplicationDto application =
                DialCoreApplicationDto.builder().id(DEPLOYMENT_ID).build();
        when(dialCoreClient.getModel(DEPLOYMENT_ID)).thenThrow(notFound());
        when(dialCoreClient.getApplication(DEPLOYMENT_ID)).thenReturn(application);
        when(dialCoreClient.getToolset(DEPLOYMENT_ID)).thenThrow(notFound());

        // When
        DeploymentInfoDto result = deploymentService.getDeployment(DEPLOYMENT_ID);

        // Then: the winner goes through the same route resolution as the by-type path
        assertThat(result).isInstanceOf(DialApplicationInfoDto.class);
        verify(schemaRouteExtractor).resolveRoutes(application);
    }

    /** Stubs all three probe legs to record the token they observe; only the model leg resolves. */
    private void stubProbesWithModelHit() {
        when(dialCoreClient.getModel(DEPLOYMENT_ID)).thenAnswer(invocation -> {
            recordProbeToken("dial-model");
            return DialCoreModelDto.builder().id(DEPLOYMENT_ID).build();
        });
        when(dialCoreClient.getApplication(DEPLOYMENT_ID)).thenAnswer(invocation -> {
            recordProbeToken("dial-application");
            throw notFound();
        });
        when(dialCoreClient.getToolset(DEPLOYMENT_ID)).thenAnswer(invocation -> {
            recordProbeToken("dial-toolset");
            throw notFound();
        });
    }

    private void recordProbeToken(String typeValue) {
        String token = AuthorizationTokenHolder.getToken();
        probeTokens.put(typeValue, token != null ? token : NO_TOKEN);
    }

    private static DialCoreClientException notFound() {
        return new DialCoreClientException(HttpStatus.NOT_FOUND, "Not found");
    }
}
