package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteUpstreamDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.client.dialcore.dto.InterfaceType;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ToolsetInfoDto;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Deployment Controller Tests")
public abstract class DeploymentFunctionalTests extends BaseFunctionalTest {

    @Autowired
    protected DialCoreClient dialCoreClient;

    @BeforeEach
    void resetMocks() {
        reset(dialCoreClient);
    }

    @Test
    @DisplayName("GET /deployments returns merged list from DIAL Core")
    void getAllDeploymentsReturnsMergedList() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(
                        DialCoreModelDto.builder()
                                .id("m1")
                                .displayName("Model 1")
                                .interfaces(List.of(InterfaceType.CHAT))
                                .build(),
                        DialCoreApplicationDto.builder()
                                .id("a1")
                                .displayName("App 1")
                                .interfaces(List.of(InterfaceType.CHAT, InterfaceType.MCP))
                                .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody())
                .extracting(DeploymentInfoDto::getDeploymentId)
                .containsExactlyInAnyOrder("m1", "a1");
        assertThat(response.getBody())
                .extracting(DeploymentInfoDto::getDisplayName)
                .containsExactlyInAnyOrder("Model 1", "App 1");
        DialModelInfoDto model = (DialModelInfoDto) response.getBody().stream()
                .filter(d -> "m1".equals(d.getDeploymentId()))
                .findFirst()
                .orElseThrow();
        assertThat(model.getDeploymentId()).isEqualTo("m1");
        assertThat(model.getInterfaces()).containsExactly(InterfaceType.CHAT);
        DialApplicationInfoDto app = (DialApplicationInfoDto) response.getBody().stream()
                .filter(d -> "a1".equals(d.getDeploymentId()))
                .findFirst()
                .orElseThrow();
        assertThat(app.getDeploymentId()).isEqualTo("a1");
        assertThat(app.getInterfaces()).containsExactly(InterfaceType.CHAT, InterfaceType.MCP);
    }

    @Test
    @DisplayName("GET /deployments/dial-model/{id} returns model")
    void getDeploymentModelReturnsModel() {
        when(dialCoreClient.getModel(eq("gpt-5")))
                .thenReturn(DialCoreModelDto.builder()
                        .id("gpt-5")
                        .displayName("GPT-5")
                        .displayVersion("2025")
                        .owner("org")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .build());

        ResponseEntity<DialModelInfoDto> response =
                restTemplate.getForEntity(apiUrl("/deployments/dial-model/gpt-5"), DialModelInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo("gpt-5");
        assertThat(response.getBody().getDisplayName()).isEqualTo("GPT-5");
        assertThat(response.getBody().getVersion()).isEqualTo("2025");
    }

    @Test
    @DisplayName("GET /deployments/dial-model/{id} returns the features DIAL Core reported, on the wire")
    void getDeploymentModelReturnsFeatures() {
        when(dialCoreClient.getModel(eq("gpt-5")))
                .thenReturn(DialCoreModelDto.builder()
                        .id("gpt-5")
                        .displayName("GPT-5")
                        .features(Map.of("rate", true, "tokenize", false))
                        .build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/dial-model/gpt-5"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("features")).isEqualTo(Map.of("rate", true, "tokenize", false));
    }

    @Test
    @DisplayName("GET /deployments/all/{id} returns the features of the deployment that resolved")
    void getDeploymentByIdReturnsFeatures() {
        when(dialCoreClient.getDeploymentById(eq("my-toolset")))
                .thenReturn(DialCoreToolsetDto.builder()
                        .id("my-toolset")
                        .features(Map.of("tools", true))
                        .build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/my-toolset"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("$type")).isEqualTo("dial-toolset");
        assertThat(response.getBody().get("features")).isEqualTo(Map.of("tools", true));
    }

    @Test
    @DisplayName("GET /deployments listing entries omit features (short projection)")
    void getAllDeploymentsOmitsFeatures() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(DialCoreModelDto.builder()
                        .id("m1")
                        .displayName("Model 1")
                        .features(Map.of("rate", true))
                        .build()));

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                apiUrl("/deployments"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).doesNotContainKey("features");
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} returns application")
    void getDeploymentApplicationReturnsApplication() {
        when(dialCoreClient.getApplication(eq("EntityExtractor")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("EntityExtractor")
                        .displayName("Entity Extractor")
                        .owner("org")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .build());

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                apiUrl("/deployments/dial-application/EntityExtractor"), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo("EntityExtractor");
        assertThat(response.getBody().getDisplayName()).isEqualTo("Entity Extractor");
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} passes a slash-containing ID to DIAL Core intact")
    void getDeploymentWithSlashContainingIdKeepsAllSegments() {
        String deploymentId = "applications/public/my-app__0.0.1";
        when(dialCoreClient.getApplication(eq(deploymentId)))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id(deploymentId)
                        .displayName("My App")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .build());

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                URI.create(apiUrl("/deployments/dial-application/" + deploymentId)), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo(deploymentId);
        verify(dialCoreClient).getApplication(deploymentId);
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} decodes percent-encoded characters in the ID once")
    void getDeploymentWithPercentEncodedIdDecodesOnce() {
        String deploymentId = "applications/public/Quick App with RAG__0.0.1";
        when(dialCoreClient.getApplication(eq(deploymentId)))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id(deploymentId)
                        .displayName("Quick App with RAG")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .build());

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                URI.create(apiUrl("/deployments/dial-application/applications/public/Quick%20App%20with%20RAG__0.0.1")),
                DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo(deploymentId);
        verify(dialCoreClient).getApplication(deploymentId);
    }

    @Test
    @DisplayName("GET /deployments/dial-toolset/{id} passes a slash-containing toolset ID to DIAL Core intact")
    void getToolsetWithSlashContainingIdKeepsAllSegments() {
        String deploymentId = "toolsets/public/3DMolVisualizer_(copy)__0.0.2";
        when(dialCoreClient.getToolset(eq(deploymentId)))
                .thenReturn(DialCoreToolsetDto.builder()
                        .id(deploymentId)
                        .displayName("3D Mol Visualizer")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .build());

        ResponseEntity<ToolsetInfoDto> response = restTemplate.getForEntity(
                URI.create(apiUrl("/deployments/dial-toolset/" + deploymentId)), ToolsetInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo(deploymentId);
        verify(dialCoreClient).getToolset(deploymentId);
    }

    @Test
    @DisplayName("GET /deployments/dial-model/ with an empty deployment ID returns 400 and calls no client method")
    void getDeploymentWithEmptyIdReturns400() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URI.create(apiUrl("/deployments/dial-model/")),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat((String) response.getBody().get("message")).contains("Deployment ID must not be empty");
        verify(dialCoreClient, never()).getModel(any());
    }

    @Test
    @DisplayName("GET /deployments/tools still routes to the tool discovery endpoint, not the by-ID wildcard")
    void toolsPathIsNotSwallowedByWildcardMapping() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URI.create(apiUrl("/deployments/tools")), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        // 'tools' is not a valid deployment type — reaching the wildcard handler would fail with
        // VALIDATION_ERROR; instead the exact /tools mapping wins and reports the missing query param.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("message")).contains("deploymentId");
        verify(dialCoreClient, never()).getToolset(any());
    }

    @Test
    @DisplayName("GET /deployments/dial-model/{id} when Core returns 404 yields 502 and UPSTREAM_NOT_FOUND")
    void getDeploymentWhenNotFoundYields502AndUpstreamNotFoundCode() {
        when(dialCoreClient.getModel(eq("missing-id")))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/dial-model/missing-id"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_NOT_FOUND");
    }

    @Test
    @DisplayName("GET /deployments/dial-model/{id} when Core returns 401 yields 502 and UPSTREAM_AUTH_ERROR")
    void getDeploymentWhenCoreReturns401Yields502AndUpstreamAuthError() {
        when(dialCoreClient.getModel(eq("some-id")))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(401), "Unauthorized"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/dial-model/some-id"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_AUTH_ERROR");
    }

    @Test
    @DisplayName("invalid deployment type returns 400 with valid types in message")
    void invalidDeploymentTypeReturns400() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/invalid-type/some-id"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("dial-model");
        assertThat(message).contains("dial-application");
        assertThat(message).contains("dial-toolset");
    }

    @Test
    @DisplayName("GET /deployments omits application routes returned by the unified endpoint")
    void getAllDeploymentsOmitsApplicationRoutes() {
        when(dialCoreClient.getDeployments(eq(null)))
                .thenReturn(List.of(DialCoreApplicationDto.builder()
                        .id("app-with-routes")
                        .displayName("App With Routes")
                        .routes(Map.of(
                                "route1",
                                DialCoreRouteDto.builder()
                                        .name("route1")
                                        .paths(List.of("/route1/.*"))
                                        .methods(List.of("GET"))
                                        .upstreams(List.of(DialCoreRouteUpstreamDto.builder()
                                                .endpoint("http://route1-upstream")
                                                .weight(1)
                                                .build()))
                                        .build()))
                        .build()));

        ResponseEntity<List<DeploymentInfoDto>> response = restTemplate.exchange(
                apiUrl("/deployments"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        DialApplicationInfoDto app = (DialApplicationInfoDto) response.getBody().get(0);
        assertThat(app.getDeploymentId()).isEqualTo("app-with-routes");
        assertThat(app.getDisplayName()).isEqualTo("App With Routes");
        // short projection: routes are a detail-endpoint concern, never returned in the listing
        assertThat(app.getRoutes()).isNull();
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} resolves schema routes when app has schemaId and no routes")
    void getDeploymentResolvesSchemaRoutes() {
        String schemaId = "https://my-schema.example/";
        when(dialCoreClient.getApplication(eq("schema-app")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("schema-app")
                        .displayName("Schema App")
                        .owner("org")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .applicationTypeSchemaId(schemaId)
                        .routes(null)
                        .build());

        ObjectMapper om = new ObjectMapper();
        JsonNode schemaJson = om.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                        Map.of(
                                "dial:paths", new String[] {"/v1/.*"},
                                "dial:methods", new String[] {"GET"},
                                "dial:upstreams",
                                        new Object[] {Map.of("dial:endpoint", "http://upstream-svc", "dial:weight", 1)},
                                "dial:rewritePath", true))));
        when(dialCoreClient.getApplicationTypeSchema(eq(schemaId))).thenReturn(schemaJson);

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                apiUrl("/deployments/dial-application/schema-app"), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRoutes()).isNotNull().containsOnlyKeys("v1");
        assertThat(response.getBody().getRoutes().get("v1").getName()).isEqualTo("v1");
        assertThat(response.getBody().getRoutes().get("v1").getPaths()).containsExactly("/v1/.*");
        assertThat(response.getBody().getRoutes().get("v1").getUpstreams()).hasSize(1);
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} merges app and schema routes, app wins on conflict")
    void getDeploymentMergesAppAndSchemaRoutes() {
        String schemaId = "https://my-schema.example/";
        when(dialCoreClient.getApplication(eq("merge-app")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("merge-app")
                        .displayName("Merge App")
                        .owner("org")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .applicationTypeSchemaId(schemaId)
                        .routes(Map.of(
                                "v1",
                                DialCoreRouteDto.builder()
                                        .name("v1")
                                        .paths(List.of("/v1/custom/.*"))
                                        .methods(List.of("POST"))
                                        .upstreams(List.of(DialCoreRouteUpstreamDto.builder()
                                                .endpoint("http://app-upstream")
                                                .weight(1)
                                                .build()))
                                        .build()))
                        .build());

        ObjectMapper om = new ObjectMapper();
        JsonNode schemaJson = om.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                                Map.of(
                                        "dial:paths", new String[] {"/v1/.*"},
                                        "dial:methods", new String[] {"GET"},
                                        "dial:upstreams",
                                                new Object[] {
                                                    Map.of("dial:endpoint", "http://schema-upstream", "dial:weight", 1)
                                                }),
                        "v2",
                                Map.of(
                                        "dial:paths", new String[] {"/v2/.*"},
                                        "dial:methods", new String[] {"GET"},
                                        "dial:upstreams",
                                                new Object[] {
                                                    Map.of("dial:endpoint", "http://schema-v2", "dial:weight", 1)
                                                }))));
        when(dialCoreClient.getApplicationTypeSchema(eq(schemaId))).thenReturn(schemaJson);

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                apiUrl("/deployments/dial-application/merge-app"), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRoutes()).containsOnlyKeys("v1", "v2");
        // v1: app-level wins on conflict
        assertThat(response.getBody().getRoutes().get("v1").getPaths()).containsExactly("/v1/custom/.*");
        assertThat(response.getBody()
                        .getRoutes()
                        .get("v1")
                        .getUpstreams()
                        .get(0)
                        .getEndpoint())
                .isEqualTo("http://app-upstream");
        // v2: schema-only route preserved
        assertThat(response.getBody().getRoutes().get("v2").getPaths()).containsExactly("/v2/.*");
        assertThat(response.getBody()
                        .getRoutes()
                        .get("v2")
                        .getUpstreams()
                        .get(0)
                        .getEndpoint())
                .isEqualTo("http://schema-v2");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} returns the model when Core resolves it as a model")
    void getDeploymentByIdReturnsModel() {
        when(dialCoreClient.getDeploymentById(eq("gpt-5")))
                .thenReturn(DialCoreModelDto.builder()
                        .id("gpt-5")
                        .displayName("GPT-5")
                        .displayVersion("2025")
                        .build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/gpt-5"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("$type")).isEqualTo("dial-model");
        assertThat(response.getBody().get("deploymentId")).isEqualTo("gpt-5");
        assertThat(response.getBody().get("displayName")).isEqualTo("GPT-5");
        assertThat(response.getBody().get("version")).isEqualTo("2025");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} returns the application with schema-resolved routes")
    void getDeploymentByIdReturnsApplicationWithResolvedRoutes() {
        String schemaId = "https://my-schema.example/";
        when(dialCoreClient.getDeploymentById(eq("schema-app")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("schema-app")
                        .displayName("Schema App")
                        .applicationTypeSchemaId(schemaId)
                        .routes(null)
                        .build());

        ObjectMapper om = new ObjectMapper();
        JsonNode schemaJson = om.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                        Map.of(
                                "dial:paths", new String[] {"/v1/.*"},
                                "dial:methods", new String[] {"GET"},
                                "dial:upstreams",
                                        new Object[] {Map.of("dial:endpoint", "http://upstream-svc", "dial:weight", 1)},
                                "dial:rewritePath", true))));
        when(dialCoreClient.getApplicationTypeSchema(eq(schemaId))).thenReturn(schemaJson);

        ResponseEntity<DialApplicationInfoDto> response =
                restTemplate.getForEntity(apiUrl("/deployments/all/schema-app"), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo("schema-app");
        assertThat(response.getBody().getRoutes()).isNotNull().containsOnlyKeys("v1");
        assertThat(response.getBody().getRoutes().get("v1").getPaths()).containsExactly("/v1/.*");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} returns the toolset when Core resolves it as a toolset")
    void getDeploymentByIdReturnsToolset() {
        when(dialCoreClient.getDeploymentById(eq("my-toolset")))
                .thenReturn(DialCoreToolsetDto.builder()
                        .id("my-toolset")
                        .displayName("My Toolset")
                        .build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/my-toolset"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("$type")).isEqualTo("dial-toolset");
        assertThat(response.getBody().get("deploymentId")).isEqualTo("my-toolset");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} when Core reports not found yields 404 NOT_FOUND")
    void getDeploymentByIdWhenNotFoundYields404() {
        when(dialCoreClient.getDeploymentById(eq("nowhere"))).thenThrow(upstreamNotFound());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/nowhere"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
        assertThat((String) response.getBody().get("message")).contains("nowhere");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} reports a 401 as UPSTREAM_AUTH_ERROR, not NOT_FOUND")
    void getDeploymentByIdUnauthorizedReturns502() {
        when(dialCoreClient.getDeploymentById(eq("unauthorized")))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(401), "Unauthorized"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/unauthorized"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_AUTH_ERROR");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} reports a 403 as 403 ACCESS_DENIED")
    void getDeploymentByIdAccessDeniedReturns403() {
        when(dialCoreClient.getDeploymentById(eq("forbidden")))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(403), "Forbidden"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/forbidden"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} reports a 500 as 502 UPSTREAM_ERROR")
    void getDeploymentByIdUpstreamErrorReturns502() {
        when(dialCoreClient.getDeploymentById(eq("broken")))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(500), "Core exploded"));

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/broken"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("GET /deployments/all/{id} passes a slash-containing ID intact")
    void getDeploymentByIdWithSlashContainingIdKeepsAllSegments() {
        String deploymentId = "applications/public/my-app__0.0.1";
        when(dialCoreClient.getDeploymentById(eq(deploymentId)))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id(deploymentId)
                        .displayName("My App")
                        .build());

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                URI.create(apiUrl("/deployments/all/" + deploymentId)), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo(deploymentId);
        verify(dialCoreClient).getDeploymentById(deploymentId);
    }

    @Test
    @DisplayName("GET /deployments/all/{id} decodes percent-encoded characters in the ID once")
    void getDeploymentByIdWithPercentEncodedIdDecodesOnce() {
        String deploymentId = "applications/public/Quick App with RAG__0.0.1";
        when(dialCoreClient.getDeploymentById(eq(deploymentId)))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id(deploymentId)
                        .displayName("Quick App with RAG")
                        .build());

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                URI.create(apiUrl("/deployments/all/applications/public/Quick%20App%20with%20RAG__0.0.1")),
                DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo(deploymentId);
        verify(dialCoreClient).getDeploymentById(deploymentId);
    }

    @Test
    @DisplayName("GET /deployments/all/ with an empty deployment ID returns 400 and calls no client method")
    void getDeploymentByIdWithEmptyIdReturns400() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                URI.create(apiUrl("/deployments/all/")), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat((String) response.getBody().get("message")).contains("Deployment ID must not be empty");
        verify(dialCoreClient, never()).getDeploymentById(any());
    }

    @Test
    @DisplayName("GET /deployments/all/{id} is not swallowed by the by-type wildcard mapping")
    void allPathIsNotSwallowedByTypedWildcardMapping() {
        when(dialCoreClient.getDeploymentById(eq("some-id")))
                .thenReturn(DialCoreModelDto.builder().id("some-id").build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/deployments/all/some-id"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        // Reaching the by-type handler would reject 'all' as an invalid deployment type with
        // VALIDATION_ERROR; instead the literal /all/** mapping wins and the lookup runs.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("$type")).isEqualTo("dial-model");
        verify(dialCoreClient).getDeploymentById("some-id");
    }

    @Test
    @DisplayName("OpenAPI spec carries minimal and full response examples for the by-ID lookup")
    void openApiSpecCarriesByIdLookupExamples() {
        ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode apiDocsRoot = new ObjectMapper().readTree(apiDocs.getBody());

        JsonNode operation =
                apiDocsRoot.path("paths").path("/api/v1/deployments/all/**").path("get");
        assertThat(operation.isMissingNode())
                .as("the by-ID lookup operation should be registered")
                .isFalse();
        JsonNode examples = operation
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(examples.propertyNames())
                .as("OpenApiExampleCustomizer should inject both examples for this operation")
                .containsExactlyInAnyOrder("minimal", "full");
        assertThat(examples.path("minimal").path("value").path("$type").asString())
                .isEqualTo("dial-model");
        assertThat(examples.path("full").path("value").path("$type").asString()).isEqualTo("dial-application");
        assertThat(examples.path("minimal").path("value").has("interfaces"))
                .as("by-id examples should carry interfaces")
                .isTrue();
        assertThat(examples.path("full").path("value").has("interfaces"))
                .as("by-id examples should carry interfaces")
                .isTrue();

        JsonNode listExamples = apiDocsRoot
                .path("paths")
                .path("/api/v1/deployments")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(listExamples.path("minimal").path("value").isArray())
                .as("list examples should be present")
                .isTrue();
        for (JsonNode entry : listExamples.path("minimal").path("value")) {
            assertThat(entry.has("interfaces"))
                    .as("list entries should carry interfaces")
                    .isTrue();
        }

        JsonNode byTypeExamples = apiDocsRoot
                .path("paths")
                .path("/api/v1/deployments/{deploymentType}/**")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(byTypeExamples.path("minimal").path("value").has("interfaces"))
                .as("by-type examples should not carry interfaces")
                .isFalse();
        assertThat(byTypeExamples.path("full").path("value").has("interfaces"))
                .as("by-type examples should not carry interfaces")
                .isFalse();
    }

    private static DialCoreClientException upstreamNotFound() {
        return new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found");
    }

    @Test
    @DisplayName("GET /deployments/dial-application/{id} returns null routes when schema fetch fails")
    void getDeploymentReturnsNullRoutesWhenSchemaFetchFails() {
        String schemaId = "https://failing-schema.example/";
        when(dialCoreClient.getApplication(eq("fail-app")))
                .thenReturn(DialCoreApplicationDto.builder()
                        .id("fail-app")
                        .displayName("Fail App")
                        .owner("org")
                        .createdAt(1000L)
                        .updatedAt(2000L)
                        .applicationTypeSchemaId(schemaId)
                        .routes(null)
                        .build());
        when(dialCoreClient.getApplicationTypeSchema(eq(schemaId)))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(500), "Schema error"));

        ResponseEntity<DialApplicationInfoDto> response = restTemplate.getForEntity(
                apiUrl("/deployments/dial-application/fail-app"), DialApplicationInfoDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDeploymentId()).isEqualTo("fail-app");
        assertThat(response.getBody().getRoutes()).isNull();
    }
}
