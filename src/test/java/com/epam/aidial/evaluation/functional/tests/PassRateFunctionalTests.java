package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.analytics.PassRateProperties;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunPassRateDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuitePassRateResponseDto;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests for {@code GET /api/v1/analytics/eval-summaries/test-case-pass-rate/{testSuiteId}}.
 */
@DisplayName("Pass Rate Functional Tests")
public abstract class PassRateFunctionalTests extends BaseFunctionalTest {

    private static final long BASE_CREATED_AT_MS = 1_700_000_000_000L;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private PassRateProperties passRateProperties;

    private UUID suiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupEvalScores();
        suiteId = metaTestDataHelper
                .createTestSuite("Pass Rate " + UUID.randomUUID())
                .getId();
    }

    @Test
    @DisplayName("Should return the two newest runs when lastN=2 out of three seeded runs")
    void shouldReturnTwoNewestRunsForLimitOfTwo() {
        UUID oldest = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS);
        UUID middle = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS + 10_000L);
        UUID newest = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS + 20_000L);

        SuitePassRateResponseDto response = getPassRate(suiteId, 2).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .containsExactly(newest, middle);
        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .doesNotContain(oldest);
    }

    @Test
    @DisplayName("Should apply the default lastN when the parameter is omitted")
    void shouldApplyDefaultLimitWhenOmitted() {
        UUID oldest = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS);
        UUID middle = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS + 10_000L);
        UUID newest = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS + 20_000L);

        SuitePassRateResponseDto response = getPassRate(suiteId, null).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("Should omit the newest run from the response when it is PENDING with no eval summaries yet")
    void shouldOmitNewestPendingRunWithoutSummaries() {
        UUID withSummary = seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS);
        UUID pendingWithoutSummary = metaTestDataHelper
                .createTestSuiteRun(suiteId, RunStatus.PENDING)
                .getId();
        metaTestDataHelper.forceRunCreatedAt(pendingWithoutSummary, BASE_CREATED_AT_MS + 10_000L);

        SuitePassRateResponseDto response = getPassRate(suiteId, null).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getRuns())
                .extracting(RunPassRateDto::getTestSuiteRunId)
                .containsExactly(withSummary);
    }

    @Test
    @DisplayName("Should include a RUNNING run with its current partial counts and status")
    void shouldIncludeRunningRunWithPartialCounts() {
        UUID runId = metaTestDataHelper
                .createTestSuiteRun(suiteId, RunStatus.RUNNING)
                .getId();
        metaTestDataHelper.forceRunCreatedAt(runId, BASE_CREATED_AT_MS);
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Case1", ExecutionStatus.SUCCESS.name(), 100L, BASE_CREATED_AT_MS);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Case2", ExecutionStatus.SUCCESS.name(), 100L, BASE_CREATED_AT_MS);

        SuitePassRateResponseDto response = getPassRate(suiteId, null).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getRuns()).hasSize(1);
        RunPassRateDto runPassRate = response.getRuns().get(0);
        assertThat(runPassRate.getStatus()).isEqualTo(RunStatus.RUNNING.name());
        assertThat(runPassRate.getTestSuiteRunId()).isEqualTo(runId);
        assertThat(runPassRate.getTotalCount()).isEqualTo(2L);
        assertThat(runPassRate.getSuccessNoVerdictCount()).isEqualTo(2L);
        assertThat(runPassRate.getFailedCount()).isZero();
        assertThat(runPassRate.getSuccessPassedCount()).isZero();
        assertThat(runPassRate.getSuccessNotPassedCount()).isZero();
    }

    @Test
    @DisplayName("Should count two turns of the same test case as totalCount 2, not collapsed to 1")
    void shouldCountEachTurnSeparately() {
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        metaTestDataHelper.forceRunCreatedAt(runId, BASE_CREATED_AT_MS);
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("Conversation")
                .executionStatus(ExecutionStatus.SUCCESS.name())
                .createdAtMs(BASE_CREATED_AT_MS)
                .turnIndex(0)
                .totalTurns(2)
                .build());
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("Conversation")
                .executionStatus(ExecutionStatus.SUCCESS.name())
                .createdAtMs(BASE_CREATED_AT_MS)
                .turnIndex(1)
                .totalTurns(2)
                .build());

        SuitePassRateResponseDto response = getPassRate(suiteId, null).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getRuns()).hasSize(1);
        assertThat(response.getRuns().get(0).getTotalCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should return 200 with an empty runs list for a suite that has no runs at all")
    void shouldReturnEmptyRunsForSuiteWithNoRuns() {
        ResponseEntity<SuitePassRateResponseDto> response = getPassRate(suiteId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRuns()).isEmpty();
    }

    @Test
    @DisplayName("Should return 200 with an empty runs list for a suite whose runs carry no eval summaries")
    void shouldReturnEmptyRunsForSuiteWithRunsButNoSummaries() {
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        metaTestDataHelper.forceRunCreatedAt(runId, BASE_CREATED_AT_MS);

        ResponseEntity<SuitePassRateResponseDto> response = getPassRate(suiteId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRuns()).isEmpty();
    }

    @Test
    @DisplayName("Should return 404 NOT_FOUND for an unknown suite id")
    void shouldReturnNotFoundForUnknownSuite() {
        ResponseEntity<Map<String, Object>> response = getPassRateError(UUID.randomUUID(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_ERROR when lastN=0")
    void shouldReturnValidationErrorForZeroLimit() {
        ResponseEntity<Map<String, Object>> response = getPassRateError(suiteId, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_ERROR naming the configured max-last-n when lastN exceeds it")
    void shouldReturnValidationErrorNamingConfiguredMaxWhenLimitExceedsIt() {
        int maxLastN = passRateProperties.getMaxLastN();

        ResponseEntity<Map<String, Object>> response = getPassRateError(suiteId, maxLastN + 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat((String) response.getBody().get("message")).contains(String.valueOf(maxLastN));
    }

    @Test
    @DisplayName("Should write nothing to meta or analytics for success, not-found, and validation-error calls")
    void shouldWriteNothingAcrossSuccessNotFoundAndValidationErrorCalls() {
        seedCompletedRunWithOneSummary(BASE_CREATED_AT_MS);
        long runsBefore = metaTestDataHelper.countTestSuiteRuns(suiteId);
        long summariesBefore = analyticsTestDataHelper.countEvalSummaries();
        long scoresBefore = analyticsTestDataHelper.countEvalScores();

        assertThat(getPassRate(suiteId, 1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metaTestDataHelper.countTestSuiteRuns(suiteId)).isEqualTo(runsBefore);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(summariesBefore);
        assertThat(analyticsTestDataHelper.countEvalScores()).isEqualTo(scoresBefore);

        assertThat(getPassRateError(UUID.randomUUID(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(metaTestDataHelper.countTestSuiteRuns(suiteId)).isEqualTo(runsBefore);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(summariesBefore);
        assertThat(analyticsTestDataHelper.countEvalScores()).isEqualTo(scoresBefore);

        assertThat(getPassRateError(suiteId, 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(metaTestDataHelper.countTestSuiteRuns(suiteId)).isEqualTo(runsBefore);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(summariesBefore);
        assertThat(analyticsTestDataHelper.countEvalScores()).isEqualTo(scoresBefore);
    }

    @Test
    @DisplayName("OpenAPI spec carries minimal and full response examples for the pass-rate endpoint")
    void openApiSpecCarriesPassRateExamples() {
        ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);

        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode apiDocsRoot = new ObjectMapper().readTree(apiDocs.getBody());
        JsonNode operation = apiDocsRoot
                .path("paths")
                .path("/api/v1/analytics/eval-summaries/test-case-pass-rate/{testSuiteId}")
                .path("get");
        assertThat(operation.isMissingNode())
                .as("the pass-rate operation should be registered")
                .isFalse();

        JsonNode examples = operation
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(examples.propertyNames())
                .as("OpenApiExampleCustomizer should inject both examples for this operation")
                .containsExactlyInAnyOrder("minimal", "full");
    }

    private UUID seedCompletedRunWithOneSummary(long createdAtMs) {
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        metaTestDataHelper.forceRunCreatedAt(runId, createdAtMs);
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "Case", ExecutionStatus.SUCCESS.name(), 100L, createdAtMs);
        return runId;
    }

    private ResponseEntity<SuitePassRateResponseDto> getPassRate(UUID testSuiteId, Integer lastN) {
        return restTemplate.getForEntity(passRateUrl(testSuiteId, lastN), SuitePassRateResponseDto.class);
    }

    private ResponseEntity<Map<String, Object>> getPassRateError(UUID testSuiteId, Integer lastN) {
        return restTemplate.exchange(
                passRateUrl(testSuiteId, lastN), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    private String passRateUrl(UUID testSuiteId, Integer lastN) {
        String path = "/analytics/eval-summaries/test-case-pass-rate/" + testSuiteId;
        return lastN != null ? apiUrl(path + "?lastN=" + lastN) : apiUrl(path);
    }
}
