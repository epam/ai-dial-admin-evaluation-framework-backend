package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClient;
import com.epam.aidial.evaluation.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreRouteUpstreamDto;
import com.epam.aidial.evaluation.service.domain.dto.deployment.ApplicationRouteDto;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapper;
import com.epam.aidial.evaluation.service.domain.mapper.DeploymentMapperImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SchemaRouteExtractor")
class SchemaRouteExtractorTest {

    private DialCoreClient dialCoreClient;
    private ObjectMapper objectMapper;
    private DeploymentMapper deploymentMapper;
    private SchemaRouteExtractor extractor;

    @BeforeEach
    void setUp() {
        dialCoreClient = mock(DialCoreClient.class);
        objectMapper = new ObjectMapper();
        deploymentMapper = new DeploymentMapperImpl();
        extractor = new SchemaRouteExtractor(dialCoreClient, objectMapper, deploymentMapper);
    }

    @Test
    @DisplayName("resolves schema routes when app has schemaId and no app-level routes")
    void resolvesSchemaRoutes() {
        String schemaId = "https://my-schema.example/";
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(schemaId)
                .routes(null)
                .build();

        JsonNode schemaJson = objectMapper.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                        Map.of(
                                "dial:paths", new String[] {"/v1/.*"},
                                "dial:methods", new String[] {"GET", "POST"},
                                "dial:upstreams",
                                        new Object[] {
                                            Map.of("dial:endpoint", "http://upstream", "dial:weight", 1, "dial:tier", 0)
                                        },
                                "dial:rewritePath", true,
                                "dial:order", 100))));
        when(dialCoreClient.getApplicationTypeSchema(schemaId)).thenReturn(schemaJson);

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNotNull().containsOnlyKeys("v1");
        ApplicationRouteDto route = result.get("v1");
        assertThat(route.getName()).isEqualTo("v1");
        assertThat(route.getPaths()).containsExactly("/v1/.*");
        assertThat(route.getMethods()).containsExactly("GET", "POST");
        assertThat(route.getUpstreams()).hasSize(1);
        assertThat(route.getUpstreams().get(0).getEndpoint()).isEqualTo("http://upstream");
        assertThat(route.getRewritePath()).isTrue();
        assertThat(route.getOrder()).isEqualTo(100);
    }

    @Test
    @DisplayName("returns null when app has no applicationTypeSchemaId")
    void returnsNullWhenNoSchemaId() {
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(null)
                .routes(null)
                .build();

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNull();
        verify(dialCoreClient, never()).getApplicationTypeSchema(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("merges app-level and schema routes with disjoint keys")
    void mergesAppAndSchemaRoutesDisjointKeys() {
        String schemaId = "https://my-schema.example/";
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(schemaId)
                .routes(Map.of(
                        "custom",
                        DialCoreRouteDto.builder()
                                .name("custom")
                                .paths(List.of("/custom/.*"))
                                .methods(List.of("POST"))
                                .upstreams(List.of(DialCoreRouteUpstreamDto.builder()
                                        .endpoint("http://custom-upstream")
                                        .weight(1)
                                        .build()))
                                .build()))
                .build();

        JsonNode schemaJson = objectMapper.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                        Map.of(
                                "dial:paths", new String[] {"/v1/.*"},
                                "dial:methods", new String[] {"GET"},
                                "dial:upstreams",
                                        new Object[] {
                                            Map.of("dial:endpoint", "http://schema-upstream", "dial:weight", 1)
                                        }))));
        when(dialCoreClient.getApplicationTypeSchema(schemaId)).thenReturn(schemaJson);

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNotNull().containsOnlyKeys("v1", "custom");
        assertThat(result.get("v1").getPaths()).containsExactly("/v1/.*");
        assertThat(result.get("v1").getUpstreams().get(0).getEndpoint()).isEqualTo("http://schema-upstream");
        assertThat(result.get("custom").getPaths()).containsExactly("/custom/.*");
        assertThat(result.get("custom").getUpstreams().get(0).getEndpoint()).isEqualTo("http://custom-upstream");
    }

    @Test
    @DisplayName("app-level route wins on conflict and warning is logged")
    void appRouteWinsOnConflict() {
        String schemaId = "https://my-schema.example/";
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(schemaId)
                .routes(Map.of(
                        "v1",
                        DialCoreRouteDto.builder()
                                .name("v1")
                                .paths(List.of("/v1/custom/.*"))
                                .methods(List.of("POST"))
                                .upstreams(List.of(DialCoreRouteUpstreamDto.builder()
                                        .endpoint("http://app-upstream")
                                        .weight(2)
                                        .build()))
                                .build()))
                .build();

        JsonNode schemaJson = objectMapper.valueToTree(Map.of(
                "dial:applicationTypeRoutes",
                Map.of(
                        "v1",
                        Map.of(
                                "dial:paths", new String[] {"/v1/.*"},
                                "dial:methods", new String[] {"GET"},
                                "dial:upstreams",
                                        new Object[] {
                                            Map.of("dial:endpoint", "http://schema-upstream", "dial:weight", 1)
                                        }))));
        when(dialCoreClient.getApplicationTypeSchema(schemaId)).thenReturn(schemaJson);

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNotNull().containsOnlyKeys("v1");
        // App-level route wins
        assertThat(result.get("v1").getPaths()).containsExactly("/v1/custom/.*");
        assertThat(result.get("v1").getMethods()).containsExactly("POST");
        assertThat(result.get("v1").getUpstreams().get(0).getEndpoint()).isEqualTo("http://app-upstream");
    }

    @Test
    @DisplayName("returns null when schema has no dial:applicationTypeRoutes")
    void returnsNullWhenSchemaHasNoRoutes() {
        String schemaId = "https://my-schema.example/";
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(schemaId)
                .routes(null)
                .build();

        JsonNode schemaJson = objectMapper.valueToTree(Map.of("type", "object", "properties", Map.of()));
        when(dialCoreClient.getApplicationTypeSchema(schemaId)).thenReturn(schemaJson);

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("returns null and logs warning when schema fetch fails")
    void returnsNullWhenSchemaFetchFails() {
        String schemaId = "https://my-schema.example/";
        DialCoreApplicationDto app = DialCoreApplicationDto.builder()
                .id("app1")
                .applicationTypeSchemaId(schemaId)
                .routes(null)
                .build();

        when(dialCoreClient.getApplicationTypeSchema(schemaId))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(500), "Internal error"));

        Map<String, ApplicationRouteDto> result = extractor.resolveRoutes(app);

        assertThat(result).isNull();
    }
}
