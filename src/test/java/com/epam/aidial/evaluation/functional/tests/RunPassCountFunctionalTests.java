package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.functional.helper.EvalScoreFixtures.scoreRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunPassCountRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.analytics.RunPassCountService;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.job.ActiveRunRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The pass rule and the stored pass counts: seeds eval summaries and score rows and calls
 * {@link RunPassCountService#record}; the completion hook is covered by running a real (MCP) suite.
 */
@DisplayName("Run pass counts")
public abstract class RunPassCountFunctionalTests extends AbstractMcpFunctionalTest {

    private static final long RUN_CREATED_AT = 5_000L;
    private static final long COMPUTED_AT = 10_000L;
    private static final UUID SMALLER_COMPUTATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LARGER_COMPUTATION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private RunPassCountService runPassCountService;

    @Autowired
    private RunPassCountRepository runPassCountRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository scoreRepository;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    @Autowired
    private McpToolInvoker mcpToolInvoker;

    @Autowired
    private ActiveRunRegistry activeRunRegistry;

    private UUID suiteId;
    private UUID runId;
    private UUID computationId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupRunPassCounts();
        suiteId = UUID.randomUUID();
        runId = UUID.randomUUID();
        computationId = UUID.randomUUID();
        reset(mcpToolInvoker);
    }

    private void summary(UUID testCaseId, ExecutionStatus status, int runIndex, UUID computation, long computedAt) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computation)
                .testCaseId(testCaseId)
                .testCaseName("case-" + testCaseId)
                .executionStatus(status.name())
                .runIndex(runIndex)
                .createdAtMs(computedAt)
                .computedAtMs(computedAt)
                .build());
    }

    private void summary(UUID testCaseId, ExecutionStatus status) {
        summary(testCaseId, status, 0, computationId, COMPUTED_AT);
    }

    private void score(UUID testCaseId, ExecutionStatus status, Boolean passed) {
        scoreRepository.saveAll(List.of(scoreRow(
                runId,
                testCaseId,
                "case-" + testCaseId,
                computationId,
                status == ExecutionStatus.SUCCESS ? 0.5 : null,
                COMPUTED_AT,
                status,
                passed)));
    }

    private RunPassCount latestRow() {
        return runPassCountRepository.findLatestRanked(List.of(suiteId), 10).get(suiteId).stream()
                .filter(r -> r.getTestSuiteRunId().equals(runId))
                .findFirst()
                .orElseThrow();
    }

    private void assertCounts(int passed, int total) {
        final RunPassCount row = latestRow();
        assertThat(row.getPassedCount()).as("passed").isEqualTo(passed);
        assertThat(row.getTotalCount()).as("total").isEqualTo(total);
    }

    @Test
    @DisplayName("threshold set, mixed outcomes: 2 passed of 4")
    void mixedOutcomes() {
        final UUID passedTrue = UUID.randomUUID();
        final UUID passedFalse = UUID.randomUUID();
        final UUID unscored = UUID.randomUUID();
        final UUID failed = UUID.randomUUID();
        summary(passedTrue, ExecutionStatus.SUCCESS);
        summary(passedFalse, ExecutionStatus.SUCCESS);
        summary(unscored, ExecutionStatus.SUCCESS);
        summary(failed, ExecutionStatus.FAILED);
        score(passedTrue, ExecutionStatus.SUCCESS, true);
        score(passedFalse, ExecutionStatus.SUCCESS, false);
        score(unscored, ExecutionStatus.SUCCESS, null);
        score(failed, ExecutionStatus.FAILED, null);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(2, 4);
    }

    @Test
    @DisplayName("a failed test case in a suite without score rows counts: 1 passed of 2")
    void failedCaseWithoutScoreDefinition() {
        summary(UUID.randomUUID(), ExecutionStatus.FAILED);
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(1, 2);
    }

    @Test
    @DisplayName("one failed repetition fails the test case, which counts once in the total")
    void oneFailedRepetitionFailsTheCase() {
        final UUID testCaseId = UUID.randomUUID();
        summary(testCaseId, ExecutionStatus.SUCCESS, 0, computationId, COMPUTED_AT);
        summary(testCaseId, ExecutionStatus.FAILED, 1, computationId, COMPUTED_AT);
        summary(testCaseId, ExecutionStatus.SUCCESS, 2, computationId, COMPUTED_AT);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(0, 1);
    }

    @Test
    @DisplayName("a metric error (non-SUCCESS summary row, no score row) fails the test case")
    void metricErrorFailsTheCase() {
        summary(UUID.randomUUID(), ExecutionStatus.ERROR);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(0, 1);
    }

    @Test
    @DisplayName("a SUCCESS test case with no score row, or a score row without a passed value, passes")
    void unscoredSuccessPasses() {
        final UUID noScoreRow = UUID.randomUUID();
        final UUID nullPassed = UUID.randomUUID();
        summary(noScoreRow, ExecutionStatus.SUCCESS);
        summary(nullPassed, ExecutionStatus.SUCCESS);
        score(nullPassed, ExecutionStatus.SUCCESS, null);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(2, 2);
    }

    @Test
    @DisplayName("a test case that only has a score row is counted, and fails when its score failed")
    void scoreOnlyTestCases() {
        score(UUID.randomUUID(), ExecutionStatus.SUCCESS, true);
        score(UUID.randomUUID(), ExecutionStatus.SUCCESS, false);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(1, 2);
    }

    @Test
    @DisplayName("rows of another computation of the run are ignored")
    void otherComputationIgnored() {
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, UUID.randomUUID(), COMPUTED_AT);
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertCounts(1, 1);
    }

    @Test
    @DisplayName("a run without test cases gets a null-computation 0-of-0 record at time 0, for either argument")
    void runWithoutTestCases() {
        final UUID otherRun = UUID.randomUUID();

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);
        runPassCountService.record(otherRun, suiteId, null, RUN_CREATED_AT);

        final List<RunPassCount> rows =
                runPassCountRepository.findLatestRanked(List.of(suiteId), 10).get(suiteId);
        assertThat(rows).extracting(RunPassCount::getTestSuiteRunId).containsExactlyInAnyOrder(runId, otherRun);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getComputationId()).isNull();
            assertThat(r.getPassedCount()).isZero();
            assertThat(r.getTotalCount()).isZero();
            assertThat(r.getComputedAtMs()).isZero();
            assertThat(r.getRunCreatedAtMs()).isEqualTo(RUN_CREATED_AT);
        });
        assertThat(analyticsTestDataHelper.countRunPassCounts()).isEqualTo(2);
    }

    @Test
    @DisplayName("the stored timestamp is the greatest eval-summary timestamp of the computation")
    void computedAtIsGreatestSummaryTimestamp() {
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, computationId, 20L);
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, computationId, 30L);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertThat(latestRow().getComputedAtMs()).isEqualTo(30L);
        assertThat(latestRow().getRunCreatedAtMs()).isEqualTo(RUN_CREATED_AT);
    }

    @Test
    @DisplayName("recording the same run and computation twice keeps one record with the first values")
    void recordingTwiceKeepsFirstWrite() {
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS);
        assertThat(runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT))
                .isTrue();
        summary(UUID.randomUUID(), ExecutionStatus.FAILED);

        final boolean second = runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertThat(second).isFalse();
        assertThat(analyticsTestDataHelper.countRunPassCounts()).isEqualTo(1);
        assertCounts(1, 1);
    }

    @Test
    @DisplayName("records of several computations are all kept and readers use the latest computation")
    void severalComputationsLatestWins() {
        final UUID older = UUID.randomUUID();
        summary(UUID.randomUUID(), ExecutionStatus.FAILED, 0, older, 100L);
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, computationId, 200L);

        runPassCountService.record(runId, suiteId, older, RUN_CREATED_AT);
        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertThat(runPassCountRepository.findRecordedComputations(List.of(runId)))
                .containsExactlyInAnyOrder(
                        new RecordedComputation(runId, older), new RecordedComputation(runId, computationId));
        assertThat(latestRow().getComputationId()).isEqualTo(computationId);
        assertCounts(1, 1);
    }

    @Test
    @DisplayName("equal computation timestamps are broken by the smaller computation id")
    void equalTimestampsSmallerIdWins() {
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, SMALLER_COMPUTATION, COMPUTED_AT);
        summary(UUID.randomUUID(), ExecutionStatus.FAILED, 0, LARGER_COMPUTATION, COMPUTED_AT);

        runPassCountService.record(runId, suiteId, LARGER_COMPUTATION, RUN_CREATED_AT);
        runPassCountService.record(runId, suiteId, SMALLER_COMPUTATION, RUN_CREATED_AT);

        assertThat(latestRow().getComputationId()).isEqualTo(SMALLER_COMPUTATION);
        assertCounts(1, 1);
    }

    @Test
    @DisplayName("a record without a computation is superseded by a computation recorded later, even at time 0")
    void nullComputationSuperseded() {
        runPassCountService.record(runId, suiteId, null, RUN_CREATED_AT);
        summary(UUID.randomUUID(), ExecutionStatus.SUCCESS, 0, computationId, 0L);

        runPassCountService.record(runId, suiteId, computationId, RUN_CREATED_AT);

        assertThat(analyticsTestDataHelper.countRunPassCounts()).isEqualTo(2);
        assertThat(latestRow().getComputationId()).isEqualTo(computationId);
        assertCounts(1, 1);
    }

    @Test
    @DisplayName("a run that completes gets a record of its computation: 2 passed of 2")
    void completedRunGetsItsRecord() {
        final TestSuiteResponseDto suite = createMcpSuite("MCP pass counts " + UUID.randomUUID(), "dial-toolset");
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "one"));
        createTestCase(suite.getId(), "TC2", Map.of("userQuery", "two"));
        when(mcpToolInvoker.callTool(eq("my-toolset"), eq("search"), any(), any(), any()))
                .thenReturn(new CallToolResult(
                        List.of(TextContent.builder("{\"answer\": \"ok\"}").build()), false, null, null));

        final UUID createdRun = startRun(suite.getId());
        final TestSuiteRunResponseDto terminal = awaitRunTerminal(createdRun, 30);

        assertThat(terminal.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        final UUID latestComputation =
                evalSummaryRepository.findLatestComputationId(createdRun).orElseThrow();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            final RunPassCount row = runPassCountRepository
                    .findLatestRanked(List.of(suite.getId()), 5)
                    .get(suite.getId())
                    .getFirst();
            assertThat(row.getTestSuiteRunId()).isEqualTo(createdRun);
            assertThat(row.getComputationId()).isEqualTo(latestComputation);
            assertThat(row.getPassedCount()).isEqualTo(2);
            assertThat(row.getTotalCount()).isEqualTo(2);
            assertThat(row.getRunCreatedAtMs())
                    .isEqualTo(
                            metaTestDataHelper.findRun(createdRun).orElseThrow().getCreatedAt());
        });
    }

    @Test
    @DisplayName("a run cancelled mid-flight gets no record")
    void cancelledRunGetsNoRecord() throws Exception {
        final TestSuiteResponseDto suite = createMcpSuite("MCP cancelled " + UUID.randomUUID(), "dial-toolset");
        createTestCase(suite.getId(), "TC1", Map.of("userQuery", "slow"));
        when(mcpToolInvoker.callTool(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(2000);
            return new CallToolResult(List.of(TextContent.builder("{}").build()), false, null, null);
        });

        final UUID createdRun = startRun(suite.getId());
        await().atMost(Duration.ofSeconds(15))
                .until(() -> metaTestDataHelper
                        .findRun(createdRun)
                        .map(r -> RunStatus.RUNNING.name().equals(r.getStatus()))
                        .orElse(false));
        final ResponseEntity<TestSuiteRunResponseDto> cancel = restTemplate.exchange(
                apiUrl("/test-suite-runs/" + createdRun + "/cancel"),
                HttpMethod.POST,
                jsonEntity(null),
                TestSuiteRunResponseDto.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        final TestSuiteRunResponseDto terminal = awaitRunTerminal(createdRun, 30);
        await().atMost(Duration.ofSeconds(15)).until(() -> activeRunRegistry.activeCount() == 0);

        assertThat(terminal.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
        assertThat(runPassCountRepository.findRecordedComputations(List.of(createdRun)))
                .isEmpty();
    }

    private UUID startRun(UUID testSuiteId) {
        final ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + testSuiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().getId();
    }
}
