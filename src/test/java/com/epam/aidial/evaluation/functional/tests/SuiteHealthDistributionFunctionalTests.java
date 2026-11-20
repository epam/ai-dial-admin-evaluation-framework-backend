package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.analytics.HealthWeekRange;
import com.epam.aidial.evaluation.service.domain.analytics.RunDeletionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End to end: {@code GET /api/v1/analytics/test-suites/health-distribution} against real Postgres. Runs on the
 * application clock: rows are seeded relative to the current week start, requests are scoped to the suites a test
 * created (a stray run recorded "now" by an earlier test lands in the current week), and a test is skipped when
 * the week turned over while it ran.
 */
@DisplayName("Suite health distribution (GET /api/v1/analytics/test-suites/health-distribution) Tests")
public abstract class SuiteHealthDistributionFunctionalTests extends BaseFunctionalTest {

    private static final long WEEK_MS = AnalyticsConstants.WEEK_MS;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private RunDeletionService runDeletionService;

    @Autowired
    private HealthWeekRange healthWeekRange;

    private long currentWeekStart;

    @BeforeEach
    void prepare() {
        analyticsTestDataHelper.cleanupRunPassCounts();
        currentWeekStart = healthWeekRange.range(1).startMs();
    }

    private long weekStartAgo(int weeksAgo) {
        return currentWeekStart - weeksAgo * WEEK_MS;
    }

    private static String path() {
        return "/api/v1/analytics/test-suites/health-distribution";
    }

    private JsonNode get(String query) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + path() + query, String.class);
        assumeTrue(currentWeekStart == healthWeekRange.range(1).startMs(), "week turned over during the test");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(response.getBody());
    }

    private HttpStatus status(String query) {
        return HttpStatus.valueOf(restTemplate
                .getForEntity(baseUrl() + path() + query, String.class)
                .getStatusCode()
                .value());
    }

    private static String scope(UUID... suites) {
        String ids = Arrays.stream(suites).map(UUID::toString).collect(Collectors.joining(","));
        return "filter=id:in:" + ids;
    }

    private static JsonNode weekAgo(JsonNode body, int weeksAgo) {
        JsonNode weeks = body.path("weeks");
        return weeks.get(weeks.size() - 1 - weeksAgo);
    }

    private static void assertCounts(JsonNode week, int healthy, int failing) {
        assertThat(week.path("healthyCount").asInt()).isEqualTo(healthy);
        assertThat(week.path("failingCount").asInt()).isEqualTo(failing);
        assertThat(week.path("suiteCount").asInt()).isEqualTo(healthy + failing);
    }

    private UUID newSuite() {
        return metaTestDataHelper.createTestSuite("health-" + UUID.randomUUID()).getId();
    }

    private UUID newMcpSuite() {
        return metaTestDataHelper
                .createTestSuiteOfType(
                        "health-mcp-" + UUID.randomUUID(),
                        SuiteType.MCP_TOOL,
                        null,
                        null,
                        "{\"id\":\"t\"}",
                        "{\"name\":\"x\",\"inputSchema\":{}}")
                .getId();
    }

    private UUID seedRun(UUID suiteId, long createdAtMs, int passed, int total) {
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createRunPassCount(
                runId, suiteId, UUID.randomUUID(), createdAtMs, passed, total, createdAtMs);
        return runId;
    }

    @Test
    @DisplayName("default request: threshold 0.75, 8 weeks oldest first, last is the current week, past weeks zero")
    void defaultRequest() {
        JsonNode body = get("");

        assertThat(body.path("threshold").asDouble()).isEqualTo(0.75);
        JsonNode weeks = body.path("weeks");
        assertThat(weeks).hasSize(8);
        assertThat(weeks.get(7).path("weekStart").asLong()).isEqualTo(currentWeekStart);
        for (int i = 1; i < 8; i++) {
            assertThat(weeks.get(i).path("weekStart").asLong()
                            - weeks.get(i - 1).path("weekStart").asLong())
                    .isEqualTo(WEEK_MS);
        }
        for (int i = 0; i < 7; i++) {
            assertCounts(weeks.get(i), 0, 0);
        }
    }

    @Test
    @DisplayName("no recorded runs: one entry per week, past weeks all zero")
    void noRecordedRuns() {
        JsonNode weeks = get("?weeks=4").path("weeks");

        assertThat(weeks).hasSize(4);
        for (int i = 0; i < 3; i++) {
            assertCounts(weeks.get(i), 0, 0);
        }
    }

    @Test
    @DisplayName("counts add up: 3 healthy and 2 failing give suiteCount 5")
    void countsAddUp() {
        List<UUID> suites = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID suite = newSuite();
            suites.add(suite);
            seedRun(suite, weekStartAgo(1) + 1_000, i < 3 ? 9 : 1, 10);
        }

        JsonNode body = get("?" + scope(suites.toArray(UUID[]::new)));

        assertCounts(weekAgo(body, 1), 3, 2);
        assertCounts(weekAgo(body, 0), 0, 0);
    }

    @Test
    @DisplayName("first millisecond of a week counts in that week, last millisecond in the week before")
    void weekBoundaries() {
        UUID first = newSuite();
        UUID last = newSuite();
        seedRun(first, weekStartAgo(1), 10, 10);
        seedRun(last, weekStartAgo(1) - 1, 0, 10);

        JsonNode body = get("?" + scope(first, last));

        assertCounts(weekAgo(body, 1), 1, 0);
        assertCounts(weekAgo(body, 2), 0, 1);
    }

    @Test
    @DisplayName("run older than the range is counted in no week")
    void runOlderThanRange() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(3) + 1_000, 10, 10);

        JsonNode weeks = get("?weeks=2&" + scope(suite)).path("weeks");

        assertThat(weeks).hasSize(2);
        assertCounts(weeks.get(0), 0, 0);
        assertCounts(weeks.get(1), 0, 0);
    }

    @Test
    @DisplayName("weeks=3 gives 3 entries, weeks=52 gives 52")
    void customWeeks() {
        assertThat(get("?weeks=3").path("weeks")).hasSize(3);
        assertThat(get("?weeks=52").path("weeks")).hasSize(52);
    }

    @Test
    @DisplayName("weeks 0, 53 and a non-integer are rejected with 400")
    void invalidWeeks() {
        assertThat(status("?weeks=0")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("?weeks=53")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("?weeks=abc")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("?weeks=1.5")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("pooled rate: 1/10 and 90/90 in one week is 91/100, healthy")
    void pooledRate() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 1, 10);
        seedRun(suite, weekStartAgo(1) + 2_000, 90, 90);

        assertCounts(weekAgo(get("?" + scope(suite)), 1), 1, 0);
    }

    @Test
    @DisplayName("rate equal to the threshold is healthy")
    void rateEqualToThreshold() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 3, 4);

        assertCounts(weekAgo(get("?" + scope(suite)), 1), 1, 0);
    }

    @Test
    @DisplayName("a suite is counted only in the weeks it ran")
    void suiteCountedOnlyInWeeksItRan() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 10, 10);

        JsonNode body = get("?" + scope(suite));

        assertCounts(weekAgo(body, 1), 1, 0);
        assertCounts(weekAgo(body, 2), 0, 0);
        assertCounts(weekAgo(body, 0), 0, 0);
    }

    @Test
    @DisplayName("runs with total 0 are not counted")
    void zeroTotalRuns() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 0, 0);

        assertCounts(weekAgo(get("?" + scope(suite)), 1), 0, 0);
    }

    @Test
    @DisplayName("same suite: rate 0.5 last week is failing, 0.9 the week before is healthy")
    void sameSuiteInTwoWeeks() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(2) + 1_000, 9, 10);
        seedRun(suite, weekStartAgo(1) + 1_000, 5, 10);

        JsonNode body = get("?" + scope(suite));

        assertCounts(weekAgo(body, 2), 1, 0);
        assertCounts(weekAgo(body, 1), 0, 1);
    }

    @Test
    @DisplayName("recomputed run: only the newer computation (9/10) contributes")
    void recomputedRun() {
        UUID suite = newSuite();
        UUID runId = UUID.randomUUID();
        long created = weekStartAgo(1) + 1_000;
        analyticsTestDataHelper.createRunPassCount(runId, suite, UUID.randomUUID(), created, 2, 10, 100);
        analyticsTestDataHelper.createRunPassCount(runId, suite, UUID.randomUUID(), created, 9, 10, 200);

        assertCounts(weekAgo(get("?" + scope(suite)), 1), 1, 0);
    }

    @Test
    @DisplayName("deleted run: a suite whose only run in the week is deleted is not counted")
    void deletedRun() {
        UUID suite = newSuite();
        UUID run = seedRun(suite, weekStartAgo(1) + 1_000, 10, 10);
        runDeletionService.markDeleted(List.of(run));

        assertCounts(weekAgo(get("?" + scope(suite)), 1), 0, 0);
    }

    @Test
    @DisplayName("threshold 0.9 makes a 0.8 suite failing and is echoed")
    void customThreshold() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 8, 10);

        JsonNode body = get("?threshold=0.9&" + scope(suite));

        assertThat(body.path("threshold").asDouble()).isEqualTo(0.9);
        assertCounts(weekAgo(body, 1), 0, 1);
    }

    @Test
    @DisplayName("threshold 0 makes every counted suite healthy")
    void thresholdZero() {
        UUID suite = newSuite();
        seedRun(suite, weekStartAgo(1) + 1_000, 0, 10);

        assertCounts(weekAgo(get("?threshold=0&" + scope(suite)), 1), 1, 0);
    }

    @Test
    @DisplayName("threshold -0.1, 1.5, abc, NaN, Infinity and -Infinity are rejected with 400")
    void invalidThreshold() {
        for (String value : List.of("-0.1", "1.5", "abc", "NaN", "Infinity", "-Infinity")) {
            assertThat(status("?threshold=" + value)).as(value).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("filter suiteType eq MCP_TOOL counts only the MCP suites")
    void filterBySuiteType() {
        UUID mcp1 = newMcpSuite();
        UUID mcp2 = newMcpSuite();
        UUID plain = newSuite();
        seedRun(mcp1, weekStartAgo(1) + 1_000, 10, 10);
        seedRun(mcp2, weekStartAgo(1) + 1_000, 1, 10);
        seedRun(plain, weekStartAgo(1) + 1_000, 10, 10);

        JsonNode body = get("?" + scope(mcp1, mcp2, plain) + "&filter=suiteType:eq:MCP_TOOL");

        assertCounts(weekAgo(body, 1), 1, 1);
    }

    @Test
    @DisplayName("a filter matching no suite gives all-zero weeks")
    void filterMatchesNothing() {
        seedRun(newSuite(), weekStartAgo(1) + 1_000, 10, 10);

        JsonNode weeks = get("?weeks=3&filter=name:eq:no-such-suite-" + UUID.randomUUID())
                .path("weeks");

        assertThat(weeks).hasSize(3);
        for (JsonNode week : weeks) {
            assertCounts(week, 0, 0);
        }
    }

    @Test
    @DisplayName("an unknown filter field is rejected with 400")
    void invalidFilter() {
        assertThat(status("?filter=noSuchField:eq:x")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("OpenAPI spec carries the health-distribution examples and described filter, threshold, weeks")
    void openApiSpecCarriesExamples() {
        ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode operation = new ObjectMapper()
                .readTree(apiDocs.getBody())
                .path("paths")
                .path(path())
                .path("get");
        assertThat(operation.isMissingNode()).isFalse();

        JsonNode examples = operation
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(examples.propertyNames()).containsExactlyInAnyOrder("minimal", "full");
        assertThat(examples.path("full").path("value").path("weeks")).hasSize(3);

        assertThat(parameterDescription(operation, "filter"))
                .contains("Format: `field:operator:value`")
                .contains("| suiteType |");
        assertThat(parameterDescription(operation, "threshold")).contains("default 0.75");
        assertThat(parameterDescription(operation, "weeks")).contains("1 to 52, default 8");
        assertThat(operation.path("parameters").toString()).doesNotContain("\"name\":\"sort\"");
    }

    private static String parameterDescription(JsonNode operation, String name) {
        for (JsonNode param : operation.path("parameters")) {
            if (name.equals(param.path("name").asString())) {
                return param.path("description").asString();
            }
        }
        return "";
    }
}
