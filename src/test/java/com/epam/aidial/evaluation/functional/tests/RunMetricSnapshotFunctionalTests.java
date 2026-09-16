package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.RunMetricSnapshotBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Run Metric Snapshot Functional Tests")
public abstract class RunMetricSnapshotFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private RunMetricSnapshotRepository runMetricSnapshotRepository;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        metaTestDataHelper.cleanupRunMetricSnapshots();
        testSuiteId = metaTestDataHelper.createTestSuite("Snapshot Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should batch create snapshots and return 201 with totalItems")
    void shouldBatchCreateSnapshots() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 3);

        var response = restTemplate.postForEntity(
                apiUrl("/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalItems()).isEqualTo(3);
        assertThat(metaTestDataHelper.countRunMetricSnapshots()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should reject non-existent testSuiteRunId with 404")
    void shouldRejectNonExistentRun() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(UUID.randomUUID(), UUID.randomUUID(), 1);

        var response = restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject at the database level a snapshot whose run does not exist")
    void shouldRejectOrphanSnapshotAtDatabaseLevel() {
        // Distinct from shouldRejectNonExistentRun (the service's own existence check, HTTP 404):
        // this bypasses the service and inserts directly, to exercise the FK constraint itself.
        UUID orphanRunId = UUID.randomUUID();

        assertThatThrownBy(() -> metaTestDataHelper.createRunMetricSnapshot(
                        orphanRunId, UUID.randomUUID(), "Accuracy", "{}", System.currentTimeMillis()))
                .isInstanceOf(DataAccessException.class);

        assertThat(metaTestDataHelper.findRunMetricSnapshotsByRunId(orphanRunId))
                .isEmpty();
    }

    @Test
    @DisplayName("Should reject empty snapshots array with 400")
    void shouldRejectEmptySnapshots() {
        RunMetricSnapshotBatchWriteRequestDto request = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of())
                .build();

        var response = restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should handle idempotent retry without duplicates")
    void shouldHandleIdempotentRetry() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 2);

        var response1 = restTemplate.postForEntity(
                apiUrl("/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var response2 = restTemplate.postForEntity(
                apiUrl("/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(metaTestDataHelper.countRunMetricSnapshots()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should list snapshots by runId filter")
    void shouldListSnapshotsByRunId() {
        UUID computationId = UUID.randomUUID();
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, computationId, 3);
        restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);

        var response = restTemplate.exchange(
                apiUrl("/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3);
    }

    @Test
    @DisplayName("Should reject missing runId filter with 400")
    void shouldRejectMissingRunIdFilter() {
        var response = restTemplate.exchange(apiUrl("/run-metric-snapshots"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should resolve latest computation ID correctly")
    void shouldResolveLatestComputationId() {
        UUID computationId1 = UUID.randomUUID();
        UUID computationId2 = UUID.randomUUID();
        long baseTime = System.currentTimeMillis();

        // Insert first computation
        RunMetricSnapshotBatchWriteRequestDto request1 = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId1)
                .computedAtMs(baseTime)
                .snapshots(List.of(buildSnapshotItem("MetricA")))
                .build();
        restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request1), BatchWriteResponseDto.class);

        // Insert second computation (later)
        RunMetricSnapshotBatchWriteRequestDto request2 = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId2)
                .computedAtMs(baseTime + 1000)
                .snapshots(List.of(buildSnapshotItem("MetricB")))
                .build();
        restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request2), BatchWriteResponseDto.class);

        // List all snapshots for the run — should be ordered by computed_at_ms DESC
        var response = restTemplate.exchange(
                apiUrl("/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> newerSnapshot =
                (Map<String, Object>) response.getBody().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> olderSnapshot =
                (Map<String, Object>) response.getBody().get(1);
        assertThat(newerSnapshot.get("computationId")).isEqualTo(computationId2.toString());
        assertThat(newerSnapshot.get("tsmdName")).isEqualTo("MetricB");
        assertThat(olderSnapshot.get("computationId")).isEqualTo(computationId1.toString());
        assertThat(olderSnapshot.get("tsmdName")).isEqualTo("MetricA");
    }

    @Test
    @DisplayName("findLatestComputationId breaks a computed_at_ms tie by the greater computation_id")
    void findLatestComputationIdBreaksTieByGreaterComputationId() {
        long sameComputedAtMs = System.currentTimeMillis();
        UUID lowerComputationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherComputationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        metaTestDataHelper.createRunMetricSnapshot(
                testSuiteRunId, higherComputationId, "MetricA", "{}", sameComputedAtMs);
        metaTestDataHelper.createRunMetricSnapshot(
                testSuiteRunId, lowerComputationId, "MetricB", "{}", sameComputedAtMs);

        Optional<UUID> latest = runMetricSnapshotRepository.findLatestComputationId(testSuiteRunId);

        assertThat(latest).contains(higherComputationId);
    }

    @Test
    @DisplayName("Deprecated alias POST returns the same status and payload as the canonical path")
    void deprecatedAliasBatchWriteMatchesCanonicalPath() {
        RunMetricSnapshotBatchWriteRequestDto canonicalRequest =
                buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 2);
        RunMetricSnapshotBatchWriteRequestDto aliasRequest = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 2);

        var canonicalResponse = restTemplate.postForEntity(
                apiUrl("/run-metric-snapshots"), jsonEntity(canonicalRequest), BatchWriteResponseDto.class);
        var aliasResponse = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(aliasRequest), BatchWriteResponseDto.class);

        // Absolute assertions: two mutual 404s would otherwise satisfy the equality checks below.
        assertThat(canonicalResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(aliasResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(canonicalResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(aliasResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        assertThat(aliasResponse.getStatusCode()).isEqualTo(canonicalResponse.getStatusCode());
        assertThat(aliasResponse.getBody()).isEqualTo(canonicalResponse.getBody());
    }

    @Test
    @DisplayName("Deprecated alias GET returns the same status and payload as the canonical path")
    void deprecatedAliasListMatchesCanonicalPath() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 2);
        restTemplate.postForEntity(apiUrl("/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);

        var canonicalResponse = restTemplate.exchange(
                apiUrl("/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});
        var aliasResponse = restTemplate.exchange(
                apiUrl("/analytics/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});

        // Absolute assertions: two mutual 404s would otherwise satisfy the equality checks below.
        assertThat(canonicalResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliasResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(canonicalResponse.getBody()).isNotEmpty();
        assertThat(aliasResponse.getBody()).isNotEmpty();
        assertThat(canonicalResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(aliasResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        assertThat(aliasResponse.getStatusCode()).isEqualTo(canonicalResponse.getStatusCode());
        assertThat(aliasResponse.getBody()).isEqualTo(canonicalResponse.getBody());
    }

    @Test
    @DisplayName("OpenAPI spec injects the renamed examples onto the canonical operations")
    void openApiSpecInjectsExamplesForCanonicalOperations() {
        JsonNode apiDocsRoot = fetchApiDocs();

        JsonNode postExamples = apiDocsRoot
                .path("paths")
                .path("/api/v1/run-metric-snapshots")
                .path("post")
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(postExamples.propertyNames())
                .as("OpenApiExampleCustomizer should inject the request example for the canonical POST")
                .contains("minimal");

        JsonNode postResponseExamples = apiDocsRoot
                .path("paths")
                .path("/api/v1/run-metric-snapshots")
                .path("post")
                .path("responses")
                .path("201")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(postResponseExamples.propertyNames())
                .as("OpenApiExampleCustomizer should inject the 201 response example for the canonical POST")
                .contains("minimal");

        JsonNode getResponseExamples = apiDocsRoot
                .path("paths")
                .path("/api/v1/run-metric-snapshots")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(getResponseExamples.propertyNames())
                .as("OpenApiExampleCustomizer should inject the 200 response example for the canonical GET")
                .contains("minimal");
    }

    @Test
    @DisplayName("OpenAPI spec marks only the alias operations deprecated")
    void openApiSpecMarksOnlyAliasOperationsDeprecated() {
        JsonNode apiDocsRoot = fetchApiDocs();

        JsonNode canonicalGet =
                apiDocsRoot.path("paths").path("/api/v1/run-metric-snapshots").path("get");
        JsonNode canonicalPost =
                apiDocsRoot.path("paths").path("/api/v1/run-metric-snapshots").path("post");
        JsonNode aliasGet = apiDocsRoot
                .path("paths")
                .path("/api/v1/analytics/run-metric-snapshots")
                .path("get");
        JsonNode aliasPost = apiDocsRoot
                .path("paths")
                .path("/api/v1/analytics/run-metric-snapshots")
                .path("post");

        assertThat(canonicalGet.path("deprecated").asBoolean(false))
                .as("canonical GET must not be deprecated")
                .isFalse();
        assertThat(canonicalPost.path("deprecated").asBoolean(false))
                .as("canonical POST must not be deprecated")
                .isFalse();
        assertThat(aliasGet.path("deprecated").asBoolean(false))
                .as("alias GET must be marked deprecated")
                .isTrue();
        assertThat(aliasPost.path("deprecated").asBoolean(false))
                .as("alias POST must be marked deprecated")
                .isTrue();
    }

    @Test
    @DisplayName("OpenAPI spec assigns four distinct operationIds across canonical and alias operations")
    void openApiSpecOperationIdsAreDistinct() {
        JsonNode apiDocsRoot = fetchApiDocs();

        String canonicalGetId = apiDocsRoot
                .path("paths")
                .path("/api/v1/run-metric-snapshots")
                .path("get")
                .path("operationId")
                .asString();
        String canonicalPostId = apiDocsRoot
                .path("paths")
                .path("/api/v1/run-metric-snapshots")
                .path("post")
                .path("operationId")
                .asString();
        String aliasGetId = apiDocsRoot
                .path("paths")
                .path("/api/v1/analytics/run-metric-snapshots")
                .path("get")
                .path("operationId")
                .asString();
        String aliasPostId = apiDocsRoot
                .path("paths")
                .path("/api/v1/analytics/run-metric-snapshots")
                .path("post")
                .path("operationId")
                .asString();

        Set<String> operationIds = new HashSet<>(List.of(canonicalGetId, canonicalPostId, aliasGetId, aliasPostId));
        assertThat(operationIds)
                .as("all four operationIds must be distinct: %s", operationIds)
                .hasSize(4);
    }

    private JsonNode fetchApiDocs() {
        ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(apiDocs.getBody());
    }

    // --- Helpers ---

    private RunMetricSnapshotBatchWriteRequestDto buildSnapshotRequest(UUID runId, UUID computationId, int count) {
        List<RunMetricSnapshotBatchWriteItemDto> items = new java.util.ArrayList<>();
        for (int ii = 0; ii < count; ii++) {
            items.add(buildSnapshotItem("Metric-" + ii));
        }
        return RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(items)
                .build();
    }

    private RunMetricSnapshotBatchWriteItemDto buildSnapshotItem(String name) {
        return RunMetricSnapshotBatchWriteItemDto.builder()
                .tsmdId(UUID.randomUUID())
                .tsmdName(name)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();
    }
}
