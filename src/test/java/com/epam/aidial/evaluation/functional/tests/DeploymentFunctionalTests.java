package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteUpstreamDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DeploymentInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialApplicationInfoDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.DialModelInfoDto;
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
                                .build(),
                        DialCoreApplicationDto.builder()
                                .id("a1")
                                .displayName("App 1")
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
        DialApplicationInfoDto app = (DialApplicationInfoDto) response.getBody().stream()
                .filter(d -> "a1".equals(d.getDeploymentId()))
                .findFirst()
                .orElseThrow();
        assertThat(app.getDeploymentId()).isEqualTo("a1");
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
    @DisplayName("GET /deployments maps application routes from the unified endpoint")
    void getAllDeploymentsMapsApplicationRoutes() {
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
        assertThat(app.getRoutes()).containsOnlyKeys("route1");
        assertThat(app.getRoutes().get("route1").getPaths()).containsExactly("/route1/.*");
        assertThat(app.getRoutes().get("route1").getUpstreams().get(0).getEndpoint())
                .isEqualTo("http://route1-upstream");
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
