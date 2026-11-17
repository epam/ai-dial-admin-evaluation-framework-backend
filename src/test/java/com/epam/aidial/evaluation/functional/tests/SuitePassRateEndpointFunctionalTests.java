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
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateSummaryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
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

    private static final double HALF_THRESHOLD = 0.5;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository scoreRepository;

    @Autowired
    private RunDeletionRepository runDeletionRepository;

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
        return metaTestDataHelper.createTestSuite("pass-rate-" + UUID.randomUUID()).getId();
    }

    /**
     * Seeds one run of {@code suiteId}: the meta run (with {@code threshold} in its snapshot when non-null), one
     * eval summary per score row and the given score rows for a single computation.
     */
    private UUID seedRun(
            UUID suiteId,
            long createdAtMs,
            Double threshold,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation) {
        return seedRun(suiteId, RunStatus.COMPLETED, createdAtMs, threshold, rowsForComputation);
    }

    private UUID seedRun(
            UUID suiteId,
            RunStatus status,
            long createdAtMs,
            Double threshold,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation) {
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId, status).getId();
        metaTestDataHelper.forceRunCreatedAt(runId, createdAtMs);
        if (threshold != null) {
            metaTestDataHelper.setRunSuiteSnapshot(
                    runId, "{\"snapshotVersion\":\"2\",\"overallScoreThreshold\":" + threshold + "}");
        }
        UUID computationId = UUID.randomUUID();
        final List<TestCaseEvalScore> scoreRows = rowsForComputation.apply(computationId).stream()
                .map(r -> withRun(r, runId))
                .toList();
        scoreRows.forEach(r -> seedSummaryForTestCase(suiteId, runId, computationId, r.getTestCaseId(), createdAtMs));
        scoreRepository.saveAll(scoreRows);
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

    @Test
    @DisplayName("two suites: latest runs average 0.75 over 2 suites, previous runs 0.0 over 1 suite")
    void latestAndPreviousAcrossTwoSuites() {
        UUID thresholdSuite = newSuite();
        UUID noThresholdSuite = newSuite();
        seedRun(thresholdSuite, 1_000L, HALF_THRESHOLD, c -> rows(c, failed(), failed()));
        seedRun(thresholdSuite, 2_000L, HALF_THRESHOLD, c -> rows(c, success(true), failed()));
        seedRun(noThresholdSuite, 3_000L, null, c -> rows(c, success(null), success(null)));

        ResponseEntity<SuitePassRateSummaryDto> response = summarize(List.of(thresholdSuite, noThresholdSuite));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuitePassRateSummaryDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.75);
        assertThat(body.getSuiteCount()).isEqualTo(2);
        assertThat(body.getPreviousPassRate()).isEqualTo(0.0);
        assertThat(body.getPreviousSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("with a threshold passed and unscored test cases count: passed, below, unscored and failed give 0.5")
    void thresholdRunCountsPassedAndUnscoredTestCases() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, success(true), success(false), success(null), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.5);
        assertThat(body.getSuiteCount()).isEqualTo(1);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("with a threshold a passed case and an omitted SUCCESS case (no score row) give 1.0")
    void thresholdPassedAndOmittedSuccessIsOne() {
        UUID suiteId = newSuite();
        seedRunWithOmittedSuccessCases(suiteId, HALF_THRESHOLD, 1, c -> rows(c, success(true)));

        assertThat(rateOf(suiteId)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("with a threshold a below-threshold case and an omitted SUCCESS case (no score row) give 0.5")
    void thresholdFailedAndOmittedSuccessIsHalf() {
        UUID suiteId = newSuite();
        seedRunWithOmittedSuccessCases(suiteId, HALF_THRESHOLD, 1, c -> rows(c, success(false)));

        assertThat(rateOf(suiteId)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("without a threshold a FAILED case and an omitted SUCCESS case (no score row) give 0.5")
    void noThresholdFailedAndOmittedSuccessIsHalf() {
        UUID suiteId = newSuite();
        seedRunWithOmittedSuccessCases(suiteId, null, 1, c -> rows(c, failed()));

        assertThat(rateOf(suiteId)).isEqualTo(0.5);
    }

    private Double rateOf(UUID suiteId) {
        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();
        assertThat(body).isNotNull();
        return body.getPassRate();
    }

    /**
     * Like {@link #seedRun}, but each score row and each of {@code omittedSuccessCases} test cases has its own
     * eval summary; the omitted ones have no score row, as the real writer leaves out unscored SUCCESS cases.
     */
    private void seedRunWithOmittedSuccessCases(
            UUID suiteId,
            Double threshold,
            int omittedSuccessCases,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation) {
        UUID runId = metaTestDataHelper
                .createTestSuiteRun(suiteId, RunStatus.COMPLETED)
                .getId();
        metaTestDataHelper.forceRunCreatedAt(runId, 1_000L);
        if (threshold != null) {
            metaTestDataHelper.setRunSuiteSnapshot(
                    runId, "{\"snapshotVersion\":\"2\",\"overallScoreThreshold\":" + threshold + "}");
        }
        UUID computationId = UUID.randomUUID();
        List<TestCaseEvalScore> scoreRows = rowsForComputation.apply(computationId).stream()
                .map(r -> withRun(r, runId))
                .toList();
        scoreRows.forEach(r -> seedSummaryForTestCase(suiteId, runId, computationId, r.getTestCaseId(), 1_000L));
        IntStream.range(0, omittedSuccessCases)
                .forEach(i -> seedSummaryForTestCase(suiteId, runId, computationId, UUID.randomUUID(), 1_000L));
        scoreRepository.saveAll(scoreRows);
    }

    private void seedSummaryForTestCase(
            UUID suiteId, UUID runId, UUID computationId, UUID testCaseId, long computedAtMs) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("case-" + testCaseId)
                .createdAtMs(1_000L)
                .computedAtMs(computedAtMs)
                .build());
    }

    @Test
    @DisplayName("without a threshold every SUCCESS test case counts: 2 SUCCESS and 1 failed give 2/3")
    void noThresholdRunCountsSuccessTestCases() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, null, c -> rows(c, success(null), success(null), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(2.0 / 3.0);
    }

    @Test
    @DisplayName("a suite with no scored run and an unknown suite id yield no rates and zero counts")
    void noScoredRunYieldsNoRates() {
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
    @DisplayName("a soft-deleted newest run is skipped: the older run is the latest and there is no previous run")
    void deletedNewestRunIsSkipped() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, success(true), success(true)));
        UUID newest = seedRun(suiteId, 2_000L, HALF_THRESHOLD, c -> rows(c, failed(), failed()));
        runDeletionRepository.insert(newest, 9_000L);
        metaTestDataHelper.deleteRun(newest);

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a newer FAILED or CANCELLED run with partial scores is skipped: the completed runs are latest and previous")
    void failedAndCancelledRunsAreSkipped() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, failed(), failed()));
        seedRun(suiteId, 2_000L, HALF_THRESHOLD, c -> rows(c, success(true), success(true)));
        seedRun(suiteId, RunStatus.FAILED, 3_000L, HALF_THRESHOLD, c -> rows(c, failed()));
        seedRun(suiteId, RunStatus.CANCELLED, 4_000L, HALF_THRESHOLD, c -> rows(c, failed()));

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
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, success(true), success(true)));
        UUID running = seedRun(suiteId, RunStatus.RUNNING, 2_000L, HALF_THRESHOLD, c -> rows(c, failed(), failed()));
        try {
            SuitePassRateSummaryDto beforeCompletion = summarize(List.of(suiteId)).getBody();

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
        metaTestDataHelper.setRunSuiteSnapshot(runId, "{\"snapshotVersion\":\"2\",\"overallScoreThreshold\":0.5}");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        List<TestCaseEvalScore> olderRows = rows(older, failed(), failed());
        List<TestCaseEvalScore> newerRows = rows(newer, success(true), success(true));
        olderRows.forEach(r -> seedSummaryForTestCase(suiteId, runId, older, r.getTestCaseId(), 1_000L));
        newerRows.forEach(r -> seedSummaryForTestCase(suiteId, runId, newer, r.getTestCaseId(), 2_000L));
        List<TestCaseEvalScore> allRows = new ArrayList<>(olderRows);
        allRows.addAll(newerRows);
        scoreRepository.saveAll(allRows.stream().map(r -> withRun(r, runId)).toList());

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("duplicate suite ids are counted once")
    void duplicateSuiteIdsCountOnce() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, success(true), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId, suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.5);
        assertThat(body.getSuiteCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty suiteIds list is rejected with 400")
    void emptyListIsBadRequest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url(), jsonEntity(SuitePassRateRequestDto.builder().suiteIds(List.of()).build()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("more distinct suite ids than the cap are rejected with 400")
    void overCapIsBadRequest() {
        List<UUID> ids = IntStream.rangeClosed(0, ValidationConstants.MAX_BATCH_SUITE_IDS)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        ResponseEntity<String> response = restTemplate.postForEntity(
                url(), jsonEntity(SuitePassRateRequestDto.builder().suiteIds(ids).build()), String.class);

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
        assertThat(requestExamples.path("full").path("value").asString())
                .contains("suiteIds");
        assertThat(responseExamples.path("full").path("value").has("previousPassRate"))
                .isTrue();
    }
}
