package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.model.TestCaseRunInput;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * GH #151 regression coverage: {@code test_suites.disabled_test_case_ids} is a legacy, no-longer-written
 * exclusion mechanism (superseded by {@code testCaseFilter}), but run selection still ANDs it in. A suite
 * carrying stale exclusions from before run conditions existed therefore executes fewer test cases than the
 * UI's "Included" count promises, and a {@code testCaseFilter} that only matches stale-listed cases can drive
 * the intersection to empty, producing a spurious 409. These tests seed the column through
 * {@link MetaTestDataHelper#forceLegacyDisabledTestCaseIds}, the only remaining writer of the column now that
 * no production code path sets it.
 */
@DisplayName("Legacy disabledTestCaseIds Functional Tests")
public abstract class LegacyDisabledTestCaseIdsFunctionalTests extends BaseFunctionalTest {

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

    @Test
    @DisplayName(
            "run selection ignores stale disabledTestCaseIds — numberOfTestCases and snapshot cover every valid case")
    void staleDisabledIdsDoNotShrinkRunWithNoFilter() {
        TestSuiteResponseDto suite = createSuiteBoundToDataset("Legacy Disabled No Filter Suite");
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC1", "q1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC2", "q2");
        TestCaseResponseDto tc3 = createTestCase(suite.getId(), "TC3", "q3");

        // Frozen exclusion from before run conditions existed: one of the three valid cases is still
        // listed as disabled, even though no production code writes this column any more.
        metaTestDataHelper.forceLegacyDisabledTestCaseIds(suite.getId(), List.of(tc2.getId()));

        mockDeploymentSuccess();
        UUID runId = createRun(suite.getId());

        // Guard-time count (persisted on the run row) must cover all 3 valid cases, not 2.
        assertThat(testSuiteRunRepository.findById(runId))
                .hasValueSatisfying(
                        run -> assertThat(run.getNumberOfTestCases()).isEqualTo(3));

        awaitRunTerminal(runId, 15);

        // Snapshot materialization must also cover all 3 valid cases, not 2.
        List<TestCaseRunInput> inputs = testCaseRunInputRepository.findByRunId(runId, 0, 100);
        assertThat(inputs).hasSize(3);
        assertThat(inputs)
                .extracting(TestCaseRunInput::getTestCaseId)
                .containsExactlyInAnyOrder(tc1.getId(), tc2.getId(), tc3.getId());
    }

    @Test
    @DisplayName(
            "run creation succeeds when testCaseFilter matches only stale-listed cases — no 409 from disabledTestCaseIds")
    void filterMatchingOnlyStaleDisabledCasesStillCreatesRun() {
        TestSuiteResponseDto suite = createSuiteBoundToDataset("Legacy Disabled Filter Suite");
        TestCaseResponseDto target = createTestCase(suite.getId(), "Target", "target-value");
        createTestCase(suite.getId(), "Other1", "other-1");
        createTestCase(suite.getId(), "Other2", "other-2");

        // The filter matches exactly the one case that the legacy column also lists as disabled, so
        // filter AND NOT-disabled is empty under the old semantics — the exact #151 409 reproduction.
        metaTestDataHelper.setSuiteTestCaseFilter(
                suite.getId(),
                "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"data::query\"},"
                        + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"target-value\"}]}");
        metaTestDataHelper.forceLegacyDisabledTestCaseIds(suite.getId(), List.of(target.getId()));

        mockDeploymentSuccess();

        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getNumberOfTestCases()).isEqualTo(1);
        UUID runId = response.getBody().getId();

        awaitRunTerminal(runId, 15);

        List<TestCaseRunInput> inputs = testCaseRunInputRepository.findByRunId(runId, 0, 100);
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).getTestCaseId()).isEqualTo(target.getId());
    }

    // --- Helper Methods ---

    private TestSuiteResponseDto createSuiteBoundToDataset(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(newDatasetWithSchema(name))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private UUID newDatasetWithSchema(String namePrefix) {
        try {
            String schemaJson = objectMapper.writeValueAsString(List.of(FieldDefinitionDto.builder()
                    .name("query")
                    .type(SchemaFieldType.STRING)
                    .required(true)
                    .build()));
            Dataset dataset = metaTestDataHelper.createDataset("legacy-disabled-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture for " + namePrefix, e);
        }
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name, String queryValue) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(Map.of("query", queryValue))
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
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

    private void mockDeploymentSuccess() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of(
                                "id",
                                "mock-1",
                                "choices",
                                List.of(Map.of("message", Map.of("content", "Mocked answer.")))),
                        null,
                        new HttpHeaders()));
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
