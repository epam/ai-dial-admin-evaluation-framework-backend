package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Suite Snapshot Functional Tests")
public abstract class SuiteSnapshotFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteRunRepository testSuiteRunRepository;

    @Autowired
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("snap-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("snapshot phase completes before RUNNING — suite_snapshot non-null and inputs rows exist")
    void snapshotPhaseCompletesBeforeRunningStatus() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Phase Suite");
        mockDeploymentSuccess();

        UUID runId = createRun(suite.getId());
        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 15);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // By the time run is terminal, snapshot must have been committed
        assertThat(testSuiteRunRepository.findById(runId))
                .hasValueSatisfying(r -> assertThat(r.getSuiteSnapshot()).isNotNull());
        assertThat(testCaseRunInputRepository.existsByRunId(runId)).isTrue();
        assertThat(terminal.getSuiteSnapshot()).isNotNull();
        assertThat(terminal.getSuiteSnapshot().getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
        assertThat(terminal.getSuiteSnapshot().getSuiteType()).isEqualTo("DEPLOYMENT");
    }

    @Test
    @DisplayName("GET /runs/{id} includes suiteSnapshot; GET /runs list has suiteSnapshot null")
    void detailIncludesSnapshotListExcludes() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot List vs Detail Suite");
        mockDeploymentSuccess();

        UUID runId = createRun(suite.getId());
        awaitRunTerminal(runId, 15);

        // Detail endpoint includes snapshot
        ResponseEntity<TestSuiteRunResponseDto> detail =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isNotNull();
        assertThat(detail.getBody().getSuiteSnapshot()).isNotNull();
        assertThat(detail.getBody().getSuiteSnapshot().getSuiteType()).isEqualTo("DEPLOYMENT");

        // List endpoint omits snapshot
        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> list = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().getContent())
                .filteredOn(r -> r.getId().equals(runId))
                .singleElement()
                .satisfies(r -> assertThat(r.getSuiteSnapshot()).isNull());
    }

    @Test
    @DisplayName(
            "snapshot isolation — suite_snapshot preserves original deployment config even after live suite is updated")
    void snapshotIsolationSuiteConfigIsPreservedInSnapshot() throws Exception {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Isolation Suite Config");
        mockDeploymentSuccess();

        UUID runId = createRun(suite.getId());

        // Wait for snapshot to be committed before the run finishes
        awaitSnapshotCommitted(runId, 10);

        // Modify the live suite: change deploymentRef.id to something different
        TestSuiteRequestDto updatedRequest = buildFullSuiteRequest(suite.getName(), "different-deployment");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updatedRequest, headers),
                TestSuiteResponseDto.class);

        awaitRunTerminal(runId, 15);

        // The snapshot in the run record must still reflect the original deployment
        String snapshotJson =
                testSuiteRunRepository.findById(runId).orElseThrow().getSuiteSnapshot();
        assertThat(snapshotJson).isNotNull();
        JsonNode snapshotNode = objectMapper.readTree(snapshotJson);
        assertThat(snapshotNode.path("deploymentRef").path("id").asString()).isEqualTo("deployment-1");
    }

    @Test
    @DisplayName(
            "snapshot isolation — test_case_run_inputs rows preserve original test case data even after live update")
    void snapshotIsolationTestCaseDataIsPreservedInInputsTable() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Isolation TC Data");
        mockDeploymentSuccess();

        UUID runId = createRun(suite.getId());

        // Wait for snapshot to be committed
        awaitSnapshotCommitted(runId, 10);

        // Fetch the test case ID
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> tcList = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases?page=0&size=100"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(tcList.getBody()).isNotNull();
        assertThat(tcList.getBody().getContent()).isNotEmpty();
        UUID tcId = tcList.getBody().getContent().get(0).getId();

        // Patch the live test case with different data
        restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/" + tcId),
                HttpMethod.PATCH,
                jsonEntity(TestCaseRequestDto.builder()
                        .data(Map.of("query", "MODIFIED_QUERY"))
                        .build()),
                TestCaseResponseDto.class);

        awaitRunTerminal(runId, 15);

        // The inputs table must still contain the original test case data
        var inputs = testCaseRunInputRepository.findByRunId(runId, 0, 100);
        assertThat(inputs).isNotEmpty();
        assertThat(inputs.get(0).getTestCaseData()).doesNotContain("MODIFIED_QUERY");
        assertThat(inputs.get(0).getTestCaseData()).contains("test query");
    }

    @Test
    @DisplayName("snapshot preserves deploymentRef.type set on the suite")
    void snapshotPreservesDeploymentRefType() throws Exception {
        // Create a suite whose deploymentRef carries a type, plus a runnable test case
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Deployment Type Suite", "dial-model");

        mockDeploymentSuccess();
        UUID runId = createRun(suite.getId());
        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 15);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // The frozen snapshot returned over HTTP carries the deployment type
        assertThat(terminal.getSuiteSnapshot()).isNotNull();
        assertThat(terminal.getSuiteSnapshot().getDeploymentRef()).isNotNull();
        assertThat(terminal.getSuiteSnapshot().getDeploymentRef().getType()).isEqualTo("dial-model");

        // And it is present in the persisted snapshot JSON
        String snapshotJson =
                testSuiteRunRepository.findById(runId).orElseThrow().getSuiteSnapshot();
        JsonNode snapshotNode = objectMapper.readTree(snapshotJson);
        assertThat(snapshotNode.path("deploymentRef").path("type").asString()).isEqualTo("dial-model");
    }

    @Test
    @DisplayName("snapshot includes datasetRef with id, version, and name of the bound dataset")
    void snapshotIncludesDatasetRef() throws Exception {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot DatasetRef Suite");
        mockDeploymentSuccess();

        UUID runId = createRun(suite.getId());
        awaitRunTerminal(runId, 15);

        String snapshotJson =
                testSuiteRunRepository.findById(runId).orElseThrow().getSuiteSnapshot();
        assertThat(snapshotJson).isNotNull();
        JsonNode snapshotNode = objectMapper.readTree(snapshotJson);
        assertThat(snapshotNode.path("snapshotVersion").asString()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
        JsonNode datasetRef = snapshotNode.path("datasetRef");
        assertThat(datasetRef.isMissingNode()).isFalse();
        UUID expectedDatasetId = metaTestDataHelper.getDatasetId(suite.getId());
        assertThat(datasetRef.path("id").asString()).isEqualTo(expectedDatasetId.toString());
        assertThat(datasetRef.path("name").asString()).startsWith("snap-");
        assertThat(datasetRef.path("version").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("snapshot excludes test cases listed in the suite's disabledTestCaseIds")
    void snapshotExcludesDisabledTestCases() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Disabled Exclusion Suite");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        // Seed an additional test case in the dataset, then add it to the suite's disabledTestCaseIds
        ResponseEntity<TestCaseResponseDto> extra = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("To Be Disabled")
                        .data(Map.of("query", "to be excluded"))
                        .build()),
                TestCaseResponseDto.class);
        assertThat(extra.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(extra.getBody()).isNotNull();
        UUID disabledId = extra.getBody().getId();
        metaTestDataHelper.appendDisabledTestCaseIds(suite.getId(), List.of(disabledId));

        mockDeploymentSuccess();
        UUID runId = createRun(suite.getId());
        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 15);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // The snapshot phase seeds test_case_run_inputs from the suite's (valid - disabled) cases.
        // Total dataset test cases = 2, disabled = 1 → input rows = 1
        var inputs = testCaseRunInputRepository.findByRunId(runId, 0, 100);
        assertThat(inputs).hasSize(1);
        // Sanity check: the row that did make it is the non-disabled case ("test query"), not "to be excluded"
        assertThat(inputs.get(0).getTestCaseData()).contains("test query");
        assertThat(inputs.get(0).getTestCaseData()).doesNotContain("to be excluded");
        assertThat(terminal.getNumberOfTestCases()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot honors the suite's testCaseFilter — only matching test cases are materialized")
    void snapshotHonorsTestCaseFilter() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Snapshot Filter Suite");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        // Seed a second test case the filter will exclude
        restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("Filtered Out")
                        .data(Map.of("query", "excluded query"))
                        .build()),
                TestCaseResponseDto.class);

        // Keep only the test case whose data::query equals "test query"
        metaTestDataHelper.setSuiteTestCaseFilter(
                suite.getId(),
                "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"data::query\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"test query\"}]}");

        mockDeploymentSuccess();
        UUID runId = createRun(suite.getId());
        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 15);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        var inputs = testCaseRunInputRepository.findByRunId(runId, 0, 100);
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).getTestCaseData()).contains("test query").doesNotContain("excluded query");
        assertThat(terminal.getNumberOfTestCases()).isEqualTo(1);
    }

    @Test
    @DisplayName("run creation returns 409 when the testCaseFilter matches no test case")
    void runRejectedWhenFilterMatchesNothing() {
        TestSuiteResponseDto suite = createTestSuiteWithTestCase("Filter Zero Match Suite");
        metaTestDataHelper.setSuiteTestCaseFilter(
                suite.getId(),
                "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"data::query\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"no-such-value\"}]}");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("no valid and enabled test cases");
    }

    @Test
    @DisplayName("V1.22 backfill rewrites v1 snapshot to v2 with synthesized datasetRef")
    void shouldBackfillLegacySnapshotsWithDatasetRef() throws Exception {
        String suiteName = "Legacy Snapshot Backfill " + UUID.randomUUID();
        TestSuite suite = metaTestDataHelper.createTestSuite(suiteName);

        // The V1.22 backfill SQL synthesizes datasetRef.id = ts.id (the joined suite's id).
        // Under V1.22's D1 invariant (dataset.id = source_suite.id) that resolves to the
        // dataset's id; but createTestSuite generates independent UUIDs for the suite and
        // its dataset, so this test asserts against suite.getId() — the exact value the SQL
        // writes — rather than dataset.getId(). Production-shape assertions about D1
        // resolution are covered by the migration's deterministic backfill behavior.

        // Pre-V1.22 producer shape: snapshotVersion="1" present (matching the original
        // @Builder.Default = "1" producer), no datasetRef key.
        Map<String, Object> legacyJson = new LinkedHashMap<>();
        legacyJson.put("snapshotVersion", "1");
        legacyJson.put("suiteType", "DEPLOYMENT");
        legacyJson.put("deploymentRef", Map.of("id", "deployment-1", "name", "Deployment One", "version", "v1"));
        legacyJson.put("requestTemplate", Map.of("urlTemplate", "/v1/chat"));
        legacyJson.put("responseColumns", List.of());
        legacyJson.put("testCaseSchema", List.of(Map.of("name", "query", "type", "STRING", "required", true)));
        legacyJson.put("inputBindings", List.of());
        String legacyJsonStr = objectMapper.writeValueAsString(legacyJson);

        TestSuiteRun run = metaTestDataHelper.createLegacyTestSuiteRun(suite.getId());
        metaTestDataHelper.setRunSuiteSnapshot(run.getId(), legacyJsonStr);

        metaTestDataHelper.applyV1_22SnapshotBackfillBlock();

        String backfilledJson =
                testSuiteRunRepository.findById(run.getId()).orElseThrow().getSuiteSnapshot();
        SuiteSnapshotDto snapshot = objectMapper.readValue(backfilledJson, SuiteSnapshotDto.class);
        assertThat(snapshot.getSnapshotVersion()).isEqualTo("2");
        assertThat(snapshot.getDatasetRef()).isNotNull();
        assertThat(snapshot.getDatasetRef().getId()).isEqualTo(suite.getId());
        assertThat(snapshot.getDatasetRef().getVersion()).isEqualTo(1L);
        assertThat(snapshot.getDatasetRef().getName()).isEqualTo("DATASET_" + suiteName);
        assertThat(snapshot.getSuiteType()).isEqualTo("DEPLOYMENT");
        assertThat(snapshot.getDeploymentRef()).isNotNull();
        assertThat(snapshot.getDeploymentRef().getId()).isEqualTo("deployment-1");
        assertThat(snapshot.getRequestTemplate()).isNotNull();
        assertThat(snapshot.getRequestTemplate().getUrlTemplate()).isEqualTo("/v1/chat");
        assertThat(snapshot.getTestCaseSchema()).hasSize(1);

        ResponseEntity<TestSuiteRunResponseDto> detail =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + run.getId()), TestSuiteRunResponseDto.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isNotNull();
        SuiteSnapshotDto httpSnapshot = detail.getBody().getSuiteSnapshot();
        assertThat(httpSnapshot).isNotNull();
        assertThat(httpSnapshot.getSnapshotVersion()).isEqualTo("2");
        assertThat(httpSnapshot.getDatasetRef()).isNotNull();
        assertThat(httpSnapshot.getDatasetRef().getId()).isEqualTo(suite.getId());
        assertThat(httpSnapshot.getDatasetRef().getVersion()).isEqualTo(1L);
        assertThat(httpSnapshot.getDatasetRef().getName()).isEqualTo("DATASET_" + suiteName);
    }

    @Test
    @DisplayName("V1.22 backfill is idempotent — repeated application produces no further changes")
    void shouldBeIdempotentOnRepeatedBackfill() throws Exception {
        String suiteName = "Idempotency Backfill " + UUID.randomUUID();
        TestSuite suite = metaTestDataHelper.createTestSuite(suiteName);

        Map<String, Object> legacyJson = new LinkedHashMap<>();
        legacyJson.put("snapshotVersion", "1");
        legacyJson.put("suiteType", "DEPLOYMENT");
        legacyJson.put("deploymentRef", Map.of("id", "deployment-1"));
        String legacyJsonStr = objectMapper.writeValueAsString(legacyJson);

        TestSuiteRun run = metaTestDataHelper.createLegacyTestSuiteRun(suite.getId());
        metaTestDataHelper.setRunSuiteSnapshot(run.getId(), legacyJsonStr);

        metaTestDataHelper.applyV1_22SnapshotBackfillBlock();
        String afterFirst =
                testSuiteRunRepository.findById(run.getId()).orElseThrow().getSuiteSnapshot();

        metaTestDataHelper.applyV1_22SnapshotBackfillBlock();
        String afterSecond =
                testSuiteRunRepository.findById(run.getId()).orElseThrow().getSuiteSnapshot();

        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("V1.22 backfill leaves NULL suite_snapshot rows untouched")
    void shouldSkipNullSnapshotRuns() {
        String suiteName = "Null Snapshot Run " + UUID.randomUUID();
        TestSuite suite = metaTestDataHelper.createTestSuite(suiteName);
        TestSuiteRun run = metaTestDataHelper.createLegacyTestSuiteRun(suite.getId());

        metaTestDataHelper.applyV1_22SnapshotBackfillBlock();

        TestSuiteRun after = testSuiteRunRepository.findById(run.getId()).orElseThrow();
        assertThat(after.getSuiteSnapshot()).isNull();
    }

    // --- Helper Methods ---

    private TestSuiteResponseDto createTestSuiteWithTestCase(String name) {
        return createTestSuiteWithTestCase(name, null);
    }

    private TestSuiteResponseDto createTestSuiteWithTestCase(String name, String deploymentType) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .type(deploymentType)
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("query")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();

        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("Test Case 1")
                        .data(Map.of("query", "test query"))
                        .build()),
                Object.class);

        return suite;
    }

    private TestSuiteRequestDto buildFullSuiteRequest(String name, String deploymentId) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description("Updated description")
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id(deploymentId)
                        .name("Updated Deployment")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("query")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();
    }

    private UUID createRun(UUID suiteId) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().getId();
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            sleep(200);
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    private void awaitSnapshotCommitted(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            boolean committed = testSuiteRunRepository
                    .findById(runId)
                    .map(r -> r.getSuiteSnapshot() != null)
                    .orElse(false);
            if (committed) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("Snapshot was not committed within " + timeoutSeconds + "s");
    }

    private void mockDeploymentSuccess() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(successResult());
    }

    private DeploymentInvocationResult successResult() {
        return new DeploymentInvocationResult(
                200,
                false,
                Map.of("id", "mock-1", "choices", List.of(Map.of("message", Map.of("content", "Mocked answer.")))),
                null,
                new HttpHeaders());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling", e);
        }
    }
}
