package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationVersionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("MetricDeclaration Controller Tests")
public abstract class MetricDeclarationFunctionalTests extends BaseFunctionalTest {

    private static final UUID SEED_ACCURACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEED_LATENCY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SEED_RELEVANCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @BeforeEach
    void insertSeedMetricDeclarations() {
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
    }

    @Test
    @DisplayName("List returns seeded metrics")
    void shouldListReturnSeededMetrics() {
        ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response = restTemplate.exchange(
                apiUrl("/metric-declarations?page=0&size=20&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(3);
        assertThat(response.getBody().getTotalElements()).isEqualTo(3L);

        assertThat(response.getBody().getContent())
                .extracting(MetricDeclarationResponseDto::getName)
                .containsExactlyInAnyOrder("Accuracy", "Latency", "Relevance");
        assertThat(response.getBody().getContent())
                .extracting(MetricDeclarationResponseDto::getId)
                .containsExactlyInAnyOrder(SEED_ACCURACY_ID, SEED_LATENCY_ID, SEED_RELEVANCE_ID);
        assertThat(response.getBody().getContent())
                .extracting(MetricDeclarationResponseDto::getProviderId)
                .containsOnly(MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID);
    }

    @Test
    @DisplayName("List returns empty when catalog is empty")
    void shouldListReturnEmptyWhenCatalogEmpty() {
        metricDeclarationTestDataProvider.clearMetricDeclarationsAndVersions();

        ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response = restTemplate.exchange(
                apiUrl("/metric-declarations?page=0&size=20&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isEmpty();
        assertThat(response.getBody().getTotalElements()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Filter by providerId returns matching declarations")
    void shouldFilterByProviderIdReturnMatching() {
        ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response = restTemplate.exchange(
                apiUrl("/metric-declarations?page=0&size=20&filter=providerId:eq:"
                        + MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID + "&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(3);
        assertThat(response.getBody().getContent())
                .extracting(MetricDeclarationResponseDto::getProviderId)
                .containsOnly(MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID);
    }

    @Test
    @DisplayName("Get by ID returns correct metric")
    void shouldGetByIdReturnCorrectMetric() {
        ResponseEntity<MetricDeclarationResponseDto> response = restTemplate.getForEntity(
                apiUrl("/metric-declarations/" + SEED_ACCURACY_ID), MetricDeclarationResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getProviderId())
                .isEqualTo(MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID);
        assertThat(response.getBody().getName()).isEqualTo("Accuracy");
        assertThat(response.getBody().getDisplayName()).isNull();
        assertThat(response.getBody().getDescription()).isEqualTo("Measures correctness of responses");
        assertThat(response.getBody().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Filtering by name works correctly")
    void shouldFilterByNameWork() {
        ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response = restTemplate.exchange(
                apiUrl("/metric-declarations?page=0&size=20&filter=name:eq:Latency&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Latency");
    }

    @Test
    @DisplayName("Sorting works correctly")
    void shouldSortingWork() {
        ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response = restTemplate.exchange(
                apiUrl("/metric-declarations?page=0&size=20&sort=name,asc&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(3);
        assertThat(response.getBody().getContent())
                .extracting(MetricDeclarationResponseDto::getName)
                .containsExactly("Accuracy", "Latency", "Relevance");
    }

    @Test
    @DisplayName("Returns 404 for non-existent metric declaration")
    void shouldReturn404ForNonExistentId() {
        UUID nonExistentId = UUID.randomUUID();
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/metric-declarations/" + nonExistentId), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when filter count exceeds 32")
    void shouldReturn400WhenFilterCountExceeds32() {
        StringBuilder url = new StringBuilder(apiUrl("/metric-declarations?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&filter=name:eq:x").append(i);
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when sort count exceeds 32")
    void shouldReturn400WhenSortCountExceeds32() {
        StringBuilder url = new StringBuilder(apiUrl("/metric-declarations?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&sort=name,asc");
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Nested
    @DisplayName("GET .../latest")
    class GetLatestVersion {

        @Test
        @DisplayName("Returns latest version when declaration has versions")
        void shouldReturnLatestVersionWhenDeclarationHasVersions() {
            ResponseEntity<MetricDeclarationVersionResponseDto> response = restTemplate.getForEntity(
                    apiUrl("/metric-declarations/" + SEED_ACCURACY_ID + "/latest"),
                    MetricDeclarationVersionResponseDto.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMetricDeclarationId()).isEqualTo(SEED_ACCURACY_ID);
            assertThat(response.getBody().getSchemaVersion()).isEqualTo(1);
            assertThat(response.getBody().getDisplayName()).isNull();
            assertThat(response.getBody().getDescription()).isEqualTo("Measures correctness of responses");
            assertThat(response.getBody().getCreatedAt()).isNotNull();
            assertThat(response.getBody().getConfigSchema()).isInstanceOf(Map.class);
            assertThat(response.getBody().getInputSchema()).isInstanceOf(Map.class);
            assertThat(response.getBody().getOutputSchema()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("Returns schemas as structured JSON objects with content")
        void shouldReturnSchemasAsJsonObjects() {
            String versionId = "990e8400-e29b-41d4-a716-446655440010";
            String configSchema = "{\"type\":\"object\",\"properties\":{\"threshold\":{\"type\":\"number\"}}}";
            String inputSchema = "{\"type\":\"object\",\"required\":[\"predictions\"]}";
            String outputSchema = "{\"type\":\"object\",\"properties\":{\"score\":{\"type\":\"number\"}}}";
            metricDeclarationTestDataProvider.insertVersionWithSchemas(
                    versionId, SEED_ACCURACY_ID.toString(), 2, configSchema, inputSchema, outputSchema);

            ResponseEntity<MetricDeclarationVersionResponseDto> response = restTemplate.getForEntity(
                    apiUrl("/metric-declarations/" + SEED_ACCURACY_ID + "/latest"),
                    MetricDeclarationVersionResponseDto.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getSchemaVersion()).isEqualTo(2);
            assertThat(body.getConfigSchema()).containsEntry("type", "object");
            assertThat(body.getInputSchema()).containsEntry("type", "object");
            assertThat(body.getOutputSchema()).containsEntry("type", "object");
        }

        @Test
        @DisplayName("Returns empty objects for empty schemas and populated object for non-empty schema")
        void shouldReturnEmptyObjectsForEmptySchemas() {
            ResponseEntity<MetricDeclarationVersionResponseDto> response = restTemplate.getForEntity(
                    apiUrl("/metric-declarations/" + SEED_ACCURACY_ID + "/latest"),
                    MetricDeclarationVersionResponseDto.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            var body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getConfigSchema()).isNotNull().isEmpty();
            assertThat(body.getInputSchema()).isNotNull().isEmpty();
            assertThat(body.getOutputSchema()).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Returns 404 when declaration does not exist")
        void shouldReturn404WhenDeclarationMissing() {
            UUID nonExistentId = UUID.randomUUID();
            ResponseEntity<String> response = restTemplate.getForEntity(
                    apiUrl("/metric-declarations/" + nonExistentId + "/latest"), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Returns 404 when declaration has no versions")
        void shouldReturn404WhenNoVersions() {
            UUID declarationWithoutVersion = UUID.fromString("880e8400-e29b-41d4-a716-446655440099");
            metricDeclarationTestDataProvider.insertSingleDeclarationWithoutVersion(
                    declarationWithoutVersion.toString(),
                    MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID,
                    "NoVersionMetric");

            ResponseEntity<String> response = restTemplate.getForEntity(
                    apiUrl("/metric-declarations/" + declarationWithoutVersion + "/latest"), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
