package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Performance smoke test for the dataset-snapshot phase (task 7.4b). Seeds {@code MAX_DISABLED_TC_IDS + 1}
 * valid test cases into a dataset plus a suite whose {@code disabledTestCaseIds.size()
 * == MAX_DISABLED_TC_IDS} (i.e. all but one case disabled). Asserts the snapshot phase completes
 * within a generous timeout, exactly one input row is materialized, and the run reaches a terminal
 * status.
 */
@DisplayName("Large Dataset Snapshot Performance Functional Tests")
public abstract class LargeDatasetSnapshotFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteRunRepository testSuiteRunRepository;

    @Autowired
    private TestCaseRunInputRepository testCaseRunInputRepository;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Test
    @DisplayName("snapshot phase materializes 1 input row from 10001-case dataset with 10000 disabled within timeout")
    void snapshotMaterializesOneRowOutOfTenThousandOne() {
        // Seed dataset + suite
        Dataset dataset = metaTestDataHelper.createDataset("perf-" + UUID.randomUUID(), "[]");
        TestSuite suite = metaTestDataHelper.createTestSuite("perf-suite-" + UUID.randomUUID(), dataset.getId());
        // Seed cases — one extra beyond the cap so exactly one survives the exclusion filter
        int total = ValidationConstants.MAX_DISABLED_TC_IDS + 1;
        List<UUID> allIds = metaTestDataHelper.seedManyTestCasesInDataset(dataset.getId(), total, true);
        assertThat(allIds).hasSize(total);
        // Disable the first MAX_DISABLED_TC_IDS — exactly one case remains enabled
        List<UUID> disabledIds = allIds.subList(0, ValidationConstants.MAX_DISABLED_TC_IDS);
        metaTestDataHelper.appendDisabledTestCaseIds(suite.getId(), disabledIds);

        mockDeploymentSuccess();

        // Start a run
        ResponseEntity<TestSuiteRunResponseDto> runResp = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(runResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = runResp.getBody().getId();

        // Wait for the snapshot to commit. With 10001 cases and disabled list capped at 10000,
        // the snapshot phase queries `findValidByDatasetIdExcludingIds` which uses
        // `WHERE NOT (id = ANY(:ids::uuid[]))` — performant at this scale (<1 second typically).
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(45);
        boolean snapshotCommitted = false;
        while (System.currentTimeMillis() < deadline) {
            if (testSuiteRunRepository
                    .findById(runId)
                    .map(r -> r.getSuiteSnapshot() != null)
                    .orElse(false)) {
                snapshotCommitted = true;
                break;
            }
            sleep(200);
        }
        assertThat(snapshotCommitted).as("Snapshot should commit within 45s").isTrue();

        // Exactly one input row was materialized — the dataset cap of 10001 minus 10000 disabled
        long inputRowCount = countInputRows(runId);
        assertThat(inputRowCount).isEqualTo(1L);

        // The run reaches a terminal status
        TestSuiteRunResponseDto terminal = awaitTerminal(runId, 60);
        assertThat(terminal.getStatus()).isIn(RunStatus.COMPLETED.name(), RunStatus.FAILED.name());
        assertThat(terminal.getNumberOfTestCases()).isEqualTo(1);
    }

    private long countInputRows(UUID runId) {
        // Paginate through inputs in batches of 500 (cheap count substitute)
        long count = 0;
        int offset = 0;
        while (true) {
            var batch = testCaseRunInputRepository.findByRunId(runId, offset, 500);
            count += batch.size();
            if (batch.size() < 500) {
                break;
            }
            offset += batch.size();
        }
        return count;
    }

    private TestSuiteRunResponseDto awaitTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            sleep(250);
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    private void mockDeploymentSuccess() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of("id", "mock-1", "choices", List.of(Map.of("message", Map.of("content", "ok")))),
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
