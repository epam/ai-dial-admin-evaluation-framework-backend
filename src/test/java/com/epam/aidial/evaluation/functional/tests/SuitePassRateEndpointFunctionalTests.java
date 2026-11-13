package com.epam.aidial.evaluation.functional.tests;

import static com.epam.aidial.evaluation.functional.helper.EvalScoreFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
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
     * eval summary and the given score rows for a single computation.
     */
    private UUID seedRun(
            UUID suiteId,
            long createdAtMs,
            Double threshold,
            Function<UUID, List<TestCaseEvalScore>> rowsForComputation) {
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        if (threshold != null) {
            metaTestDataHelper.setRunSuiteSnapshot(
                    runId, "{\"snapshotVersion\":\"2\",\"overallScoreThreshold\":" + threshold + "}");
        }
        UUID computationId = UUID.randomUUID();
        seedSummary(suiteId, runId, computationId, createdAtMs, createdAtMs);
        scoreRepository.saveAll(rowsForComputation.apply(computationId).stream()
                .map(r -> withRun(r, runId))
                .toList());
        return runId;
    }

    private static TestCaseEvalScore withRun(TestCaseEvalScore score, UUID runId) {
        score.setTestSuiteRunId(runId);
        return score;
    }

    private void seedSummary(UUID suiteId, UUID runId, UUID computationId, long createdAtMs, long computedAtMs) {
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case")
                .createdAtMs(createdAtMs)
                .computedAtMs(computedAtMs)
                .build());
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
    @DisplayName("with a threshold only passed test cases count: passed, below, unscored and failed give 0.25")
    void thresholdRunCountsOnlyPassedTestCases() {
        UUID suiteId = newSuite();
        seedRun(suiteId, 1_000L, HALF_THRESHOLD, c -> rows(c, success(true), success(false), success(null), failed()));

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(0.25);
        assertThat(body.getSuiteCount()).isEqualTo(1);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
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

        SuitePassRateSummaryDto body = summarize(List.of(suiteId)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPassRate()).isEqualTo(1.0);
        assertThat(body.getPreviousPassRate()).isNull();
        assertThat(body.getPreviousSuiteCount()).isZero();
    }

    @Test
    @DisplayName("a re-scored run is read at its latest computation only")
    void usesLatestComputationOfRun() {
        UUID suiteId = newSuite();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        metaTestDataHelper.setRunSuiteSnapshot(runId, "{\"snapshotVersion\":\"2\",\"overallScoreThreshold\":0.5}");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        seedSummary(suiteId, runId, older, 1_000L, 1_000L);
        seedSummary(suiteId, runId, newer, 1_000L, 2_000L);
        List<TestCaseEvalScore> allRows = new ArrayList<>(rows(older, failed(), failed()));
        allRows.addAll(rows(newer, success(true), success(true)));
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
