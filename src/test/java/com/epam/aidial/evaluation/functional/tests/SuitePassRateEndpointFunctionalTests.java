package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.functional.helper.EvalScoreFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.analytics.RunPassCountService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End to end: {@code POST /api/v1/analytics/test-suites/pass-rate} against real Postgres. */
@DisplayName("Suite pass-rate summary (POST /api/v1/analytics/test-suites/pass-rate) Tests")
public abstract class SuitePassRateEndpointFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository scoreRepository;

    @Autowired
    private RunDeletionRepository runDeletionRepository;

    @Autowired
    private RunPassCountService runPassCountService;

    @BeforeEach
    void cleanRunPassCounts() {
        analyticsTestDataHelper.cleanupRunPassCounts();
    }

    private String url() {
        return baseUrl() + "/api/v1/analytics/test-suites/pass-rate";
    }

    private ResponseEntity<SuitePassRateSummaryDto> summarize(List<UUID> suiteIds) {
        return restTemplate.postForEntity(
                url(),
                jsonEntity(SuitePassRateRequestDto.builder().suiteIds(suiteIds).build()),
                SuitePassRateSummaryDto.class);
    }

    private UUID newSuite() {
        return metaTestDataHelper
                .createTestSuite("pass-rate-" + UUID.randomUUID())
                .getId();
    }

    /**
     * Seeds one run of {@code suiteId}: the meta run, one eval summary per test case (its status is the score row's
     * status) and the score rows for a single computation. Extra summaries without a score row model test cases
     * the writer left unscored or that failed before scoring. A COMPLETED run is then recorded through
     * {@link RunPassCountService#record}, as the job does on completion; other runs are never recorded.
     */
    private UUID seedRun(
            UUID suiteId,
            long createdAtMs,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation,
            ExecutionStatus... summariesWithoutScoreRow) {
        return seedRun(suiteId, RunStatus.COMPLETED, createdAtMs, rowsForComputation, summariesWithoutScoreRow);
    }

    private UUID seedRun(
            UUID suiteId,
            RunStatus status,
            long createdAtMs,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation,
            ExecutionStatus... summariesWithoutScoreRow) {
        final UUID runId =
                metaTestDataHelper.createTestSuiteRun(suiteId, status).getId();
        metaTestDataHelper.forceRunCreatedAt(runId, createdAtMs);
        final UUID computationId = UUID.randomUUID();
        final List<TestCaseEvalScore> scoreRows = rowsForComputation.apply(computationId).stream()
                .map(r -> withRun(r, runId))
                .toList();
        scoreRows.forEach(r -> seedSummaryForTestCase(
                suiteId, runId, computationId, r.getTestCaseId(), r.getExecutionStatus(), createdAtMs));
        for (final ExecutionStatus summaryStatus : summariesWithoutScoreRow) {
            seedSummaryForTestCase(suiteId, runId, computationId, UUID.randomUUID(), summaryStatus, createdAtMs);
        }
        scoreRepository.saveAll(scoreRows);
        if (status == RunStatus.COMPLETED) {
            runPassCountService.record(runId, suiteId, computationId, createdAtMs);
        }
        return runId;
    }

    private static TestCaseEvalScore withRun(TestCaseEvalScore score, UUID runId) {
        score.setTestSuiteRunId(runId);
        return score;
    }

    private static List<TestCaseEvalScore> rows(UUID computationId, Object[]... statusAndPassed) {
        List<TestCaseEvalScore> rows = new ArrayList<>();
        for (Object[] r : statusAndPassed) {
            rows.add(row(UUID.randomUUID(), computationId, (ExecutionStatus) r[0], (Boolean) r[1]));
        }
        return rows;
    }

    private static Object[] success(Boolean passed) {
        return new Object[] {ExecutionStatus.SUCCESS, passed};
    }

    private static Object[] failed() {
        return new Object[] {ExecutionStatus.FAILED, null};
    }

    private void seedSummaryForTestCase(
            UUID suiteId, UUID runId, UUID computationId, UUID testCaseId, ExecutionStatus status, long computedAtMs) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("case-" + testCaseId)
                .executionStatus(status.name())
                .createdAtMs(computedAtMs)
                .computedAtMs(computedAtMs)
                .build());
    }

    private Double rateOf(UUID suiteId) {
        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();
        assertThat(body).isNotNull();
        return body.getPassRate();
    }

    @Test
    @DisplayName("two suites: latest runs average 0.75 over 2 suites, previous runs 0.0 over 1 suite")
    void latestAndPreviousAcrossTwoSuites() {
        UUID firstSuite = newSuite();
        UUID secondSuite = newSuite();
        seedRun(firstSuite, 1_000L, c -> rows(c, failed(), failed()));
        seedRun(firstSuite, 2_000L, c -> rows(c, success(true), failed()));
        seedRun(secondSuite, 3_000L, c -> rows(c, success(null), success(null)));

        ResponseEntity<SuitePassRateSummaryDto> response = summarize(List.of(firstSuite, secondSuite));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuitePassRateSummaryDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.75);
        assertThat(body.getSuiteCount()).isEqualTo(2);
        assertThat(body.getPreviousPassRate()).isEqualTo(0.0);
        assertThat(body.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("passed, below-threshold, unscored and failed test cases give 0.5")
    void mixedOutcomesGiveHalf() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true), success(false), success(null), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.5);
        assertThat(body.getSuiteCount()).isEqualTo(1);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a passed case and a SUCCESS case without a score row give 1.0")
    void passedAndOmittedSuccessIsOne() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true)), ExecutionStatus.SUCCESS);

        assertThat(rateOf(suiteId)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a below-threshold case with a SUCCESS case lacking a score row gives 0.5")
    void belowThresholdAndOmittedSuccessIsHalf() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(false)), ExecutionStatus.SUCCESS);

        assertThat(rateOf(suiteId)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a failed test case without a score row and a SUCCESS one give 0.5")
    void failedWithoutScoreRowGivesHalf() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> List.of(), ExecutionStatus.FAILED, ExecutionStatus.SUCCESS);

        assertThat(rateOf(suiteId)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a metric error without a score row counts in the denominator and not as passed")
    void metricErrorWithoutScoreRowIsNotPassed() {
        UUID suiteId = newSuite();
        // the second test case executed successfully but its metric failed: not-SUCCESS summary row, no score row
        seedRun(suiteId, 1_000L, c -> rows(c, success(true)), ExecutionStatus.FAILED);

        assertThat(rateOf(suiteId)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("without a score threshold every SUCCESS test case counts: 2 SUCCESS and 1 failed give 2/3")
    void noThresholdRunCountsSuccessTestCases() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(null), success(null), failed()));

        assertThat(rateOf(suiteId)).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("a suite with no recorded run and an unknown suite id yield no rates and zero counts")
    void noRecordedRunYieldsNoRates() {
        ResponseEntity<SuitePassRateSummaryDto> response = summarize(List.of(newSuite(), UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuitePassRateSummaryDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isNull();
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getSuiteCount()).isZero();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a completed run that is not recorded yet is ignored: the older recorded run is the latest")
    void completedRunNotRecordedYetIsIgnored() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true), success(true)));
        UUID newest = metaTestDataHelper
                .createTestSuiteRun(suiteId, RunStatus.COMPLETED)
                .getId();
        metaTestDataHelper.forceRunCreatedAt(newest, 2_000L);

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
        assertThat(body.getSuiteCount()).isEqualTo(1);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a soft-deleted newest run is skipped: the older run is the latest and there is no previous run")
    void deletedNewestRunIsSkipped() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true), success(true)));
        UUID newest = seedRun(suiteId, 2_000L, c -> rows(c, failed(), failed()));
        runDeletionRepository.insert(newest, 9_000L);
        metaTestDataHelper.deleteRun(newest);

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a newer FAILED or CANCELLED run with partial scores is skipped: the completed runs are latest and"
            + " previous")
    void failedAndCancelledRunsAreSkipped() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, failed(), failed()));
        seedRun(suiteId, 2_000L, c -> rows(c, success(true), success(true)));
        seedRun(suiteId, RunStatus.FAILED, 3_000L, c -> rows(c, failed()));
        seedRun(suiteId, RunStatus.CANCELLED, 4_000L, c -> rows(c, failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
        assertThat(body.getSuiteCount()).isEqualTo(1);
        assertThat(body.getPreviousPassRate()).isEqualTo(0.0);
        assertThat(body.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a RUNNING run whose scores are already written is not the latest until it completes")
    void runningRunIsSkippedUntilCompleted() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true), success(true)));
        UUID running = seedRun(suiteId, RunStatus.RUNNING, 2_000L, c -> rows(c, failed(), failed()));
        try {
            SuitePassRateSummaryDto beforeCompletion =
                    summarize(List.of(suiteId)).getBody();

            assertThat(beforeCompletion).isNotNull();
            assertThat(beforeCompletion.getPassRate()).isEqualTo(1.0);
            assertThat(beforeCompletion.getPreviousSuiteCount()).isZero();
        } finally {
            // A RUNNING row left lying around would count toward other tests' active-run limits.
            metaTestDataHelper.deleteRun(running);
        }
    }

    @Test
    @DisplayName("a re-scored run is read at its latest computation only")
    void usesLatestComputationOfRun() {
        UUID suiteId = newSuite();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        List<TestCaseEvalScore> olderRows = rows(older, failed(), failed());
        List<TestCaseEvalScore> newerRows = rows(newer, success(true), success(true));
        olderRows.forEach(
                r -> seedSummaryForTestCase(suiteId, runId, older, r.getTestCaseId(), r.getExecutionStatus(), 1_000L));
        newerRows.forEach(
                r -> seedSummaryForTestCase(suiteId, runId, newer, r.getTestCaseId(), r.getExecutionStatus(), 2_000L));
        List<TestCaseEvalScore> allRows = new ArrayList<>(olderRows);
        allRows.addAll(newerRows);
        scoreRepository.saveAll(allRows.stream().map(r -> withRun(r, runId)).toList());
        runPassCountService.record(runId, suiteId, older, 1_000L);
        runPassCountService.record(runId, suiteId, newer, 1_000L);

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("duplicate suite ids are counted once")
    void duplicateSuiteIdsCountOnce() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, c -> rows(c, success(true), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId, suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.5);
        assertThat(body.getSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty suiteIds list is rejected with 400")
    void emptyListIsBadRequest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url(),
                jsonEntity(SuitePassRateRequestDto.builder().suiteIds(List.of()).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("more distinct suite ids than the cap are rejected with 400")
    void overCapIsBadRequest() {
        List<UUID> ids = IntStream.rangeClosed(0, ValidationConstants.MAX_BATCH_SUITE_IDS)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        ResponseEntity<String> response = restTemplate.postForEntity(
                url(),
                jsonEntity(SuitePassRateRequestDto.builder().suiteIds(ids).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("OpenAPI spec carries minimal and full request and response examples for the endpoint")
    void openApiSpecCarriesExamples() {
        ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode operation = new ObjectMapper()
                .readTree(apiDocs.getBody())
                .path("paths")
                .path("/api/v1/analytics/test-suites/pass-rate")
                .path("post");
        assertThat(operation.isMissingNode()).isFalse();

        JsonNode requestExamples = operation
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("examples");
        JsonNode responseExamples = operation
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");

        assertThat(requestExamples.propertyNames()).containsExactlyInAnyOrder("minimal", "full");
        assertThat(responseExamples.propertyNames()).containsExactlyInAnyOrder("minimal", "full");
        assertThat(requestExamples.path("full").path("value").asString()).contains("suiteIds");
        assertThat(responseExamples.path("full").path("value").has("previousPassRate"))
                .isTrue();
    }
}
