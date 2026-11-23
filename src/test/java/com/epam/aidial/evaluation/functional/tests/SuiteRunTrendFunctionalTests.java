package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.analytics.RunDeletionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** End to end: {@code GET /api/v1/analytics/test-suites/run-trends} against real Postgres. */
@DisplayName("Suite run trends (GET /api/v1/analytics/test-suites/run-trends) Tests")
public abstract class SuiteRunTrendFunctionalTests extends BaseFunctionalTest {

    private static final double EPSILON = 1e-9;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private RunDeletionService runDeletionService;

    @BeforeEach
    void cleanPassCounts() {
        analyticsTestDataHelper.cleanupRunPassCounts();
    }

    private JsonNode get(String query) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + path() + query, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new ObjectMapper().readTree(response.getBody());
    }

    private HttpStatus status(String query) {
        return HttpStatus.valueOf(restTemplate
                .getForEntity(baseUrl() + path() + query, String.class)
                .getStatusCode()
                .value());
    }

    private static String path() {
        return "/api/v1/analytics/test-suites/run-trends";
    }

    private UUID newSuite(String name) {
        return metaTestDataHelper
                .createTestSuite(name + "-" + UUID.randomUUID())
                .getId();
    }

    /** Seeds one recorded run with its own computation; returns the run id. */
    private UUID seedRun(UUID suiteId, long createdAtMs, int passed, int total) {
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createRunPassCount(
                runId, suiteId, UUID.randomUUID(), createdAtMs, passed, total, createdAtMs);
        return runId;
    }

    private UUID seedRunWithId(UUID runId, UUID suiteId, long createdAtMs, int passed, int total) {
        analyticsTestDataHelper.createRunPassCount(
                runId, suiteId, UUID.randomUUID(), createdAtMs, passed, total, createdAtMs);
        return runId;
    }

    private static List<String> suiteIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("content").forEach(item -> ids.add(item.path("suiteId").asString()));
        return ids;
    }

    @Test
    @DisplayName("three runs: rates in creation order, latest 1.0, previous 0.75, delta 0.25")
    void threeRuns() {
        UUID suite = newSuite("three");
        seedRun(suite, 3_000, 4, 4);
        seedRun(suite, 1_000, 2, 4);
        seedRun(suite, 2_000, 3, 4);

        JsonNode item = get("?page=0&size=100").path("content").get(0);

        assertThat(item.path("suiteId").asString()).isEqualTo(suite.toString());
        List<Double> rates = new ArrayList<>();
        item.path("runs").forEach(r -> rates.add(r.path("passRate").asDouble()));
        assertThat(rates).containsExactly(0.5, 0.75, 1.0);
        assertThat(item.path("latestPassRate").asDouble()).isEqualTo(1.0);
        assertThat(item.path("previousPassRate").asDouble()).isEqualTo(0.75);
        assertThat(item.path("delta").asDouble()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("twelve runs: the 10 newest are returned, oldest of those first")
    void tenRunCap() {
        UUID suite = newSuite("cap");
        for (int i = 1; i <= 12; i++) {
            seedRun(suite, i * 1_000L, 1, 2);
        }

        JsonNode runs = get("?page=0&size=100").path("content").get(0).path("runs");

        assertThat(runs).hasSize(10);
        assertThat(runs.get(0).path("createdAt").asLong()).isEqualTo(3_000L);
        assertThat(runs.get(9).path("createdAt").asLong()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("single run: latest rate present, previous rate and delta absent")
    void singleRun() {
        UUID suite = newSuite("single");
        seedRun(suite, 1_000, 2, 5);

        JsonNode item = get("?page=0&size=100").path("content").get(0);

        assertThat(item.path("latestPassRate").asDouble()).isEqualTo(0.4);
        assertThat(item.has("previousPassRate")).isFalse();
        assertThat(item.has("delta")).isFalse();
    }

    @Test
    @DisplayName("latest run with total 0: latest rate and delta absent, previous 0.9, run kept with absent passRate")
    void zeroTotalLatestRun() {
        UUID suite = newSuite("zero");
        seedRun(suite, 1_000, 9, 10);
        seedRun(suite, 2_000, 0, 0);

        JsonNode item = get("?page=0&size=100").path("content").get(0);

        assertThat(item.has("latestPassRate")).isFalse();
        assertThat(item.has("delta")).isFalse();
        assertThat(item.path("previousPassRate").asDouble()).isEqualTo(0.9);
        assertThat(item.path("runs")).hasSize(2);
        assertThat(item.path("runs").get(1).has("passRate")).isFalse();
        assertThat(item.path("runs").get(1).path("totalCount").asInt()).isZero();
    }

    @Test
    @DisplayName("recomputed run: the run appears once with the counts of its latest computation")
    void recomputedRun() {
        UUID suite = newSuite("recomputed");
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createRunPassCount(runId, suite, UUID.randomUUID(), 1_000, 1, 10, 100);
        analyticsTestDataHelper.createRunPassCount(runId, suite, UUID.randomUUID(), 1_000, 9, 10, 200);

        JsonNode runs = get("?page=0&size=100").path("content").get(0).path("runs");

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).path("passedCount").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("deleted run: the previous run becomes latest; a suite whose only run is deleted is not listed")
    void deletedRun() {
        UUID suite = newSuite("deleted");
        seedRun(suite, 1_000, 5, 10);
        UUID newest = seedRun(suite, 2_000, 10, 10);
        UUID onlyDeleted = newSuite("only-deleted");
        UUID onlyRun = seedRun(onlyDeleted, 1_500, 1, 1);
        runDeletionService.markDeleted(List.of(newest, onlyRun));

        JsonNode page = get("?page=0&size=100");

        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        JsonNode item = page.path("content").get(0);
        assertThat(item.path("suiteId").asString()).isEqualTo(suite.toString());
        assertThat(item.path("latestPassRate").asDouble()).isEqualTo(0.5);
        assertThat(item.path("runs")).hasSize(1);
    }

    @Test
    @DisplayName("worst first across pages of 2 with a total of 5 on every page")
    void worstFirstAcrossPages() {
        double[] rates = {0.9, 0.1, 0.5, 0.3, 0.7};
        List<UUID> suites = new ArrayList<>();
        for (int i = 0; i < rates.length; i++) {
            UUID suite = newSuite("rank");
            suites.add(suite);
            seedRun(suite, 1_000, (int) Math.round(rates[i] * 10), 10);
        }

        JsonNode page0 = get("?page=0&size=2");
        JsonNode page1 = get("?page=1&size=2");
        JsonNode page2 = get("?page=2&size=2");

        assertThat(suiteIds(page0))
                .containsExactly(suites.get(1).toString(), suites.get(3).toString());
        assertThat(suiteIds(page1))
                .containsExactly(suites.get(2).toString(), suites.get(4).toString());
        assertThat(suiteIds(page2)).containsExactly(suites.get(0).toString());
        for (JsonNode page : List.of(page0, page1, page2)) {
            assertThat(page.path("totalElements").asLong()).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("a suite whose latest run has no test cases sorts after a suite with rate 1.0")
    void absentRateLast() {
        UUID noCases = newSuite("no-cases");
        seedRun(noCases, 2_000, 0, 0);
        UUID perfect = newSuite("perfect");
        seedRun(perfect, 1_000, 5, 5);

        assertThat(suiteIds(get("?page=0&size=100"))).containsExactly(perfect.toString(), noCases.toString());
    }

    @Test
    @DisplayName("size above the maximum and a negative page are rejected with 400")
    void invalidPaging() {
        assertThat(status("?page=0&size=100000")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status("?page=-1&size=10")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("evaluatee per suite type: deployment, MCP tool (with toolName) and route")
    void evaluateePerSuiteType() {
        UUID deployment = metaTestDataHelper
                .createTestSuiteOfType(
                        "dep-" + UUID.randomUUID(),
                        SuiteType.DEPLOYMENT,
                        "{\"id\":\"gpt-4o\",\"name\":\"GPT-4o\",\"type\":\"dial-model\"}",
                        null,
                        null,
                        null)
                .getId();
        UUID mcp = metaTestDataHelper
                .createTestSuiteOfType(
                        "mcp-" + UUID.randomUUID(),
                        SuiteType.MCP_TOOL,
                        null,
                        null,
                        "{\"id\":\"docs-toolset\",\"name\":\"Docs toolset\",\"type\":\"dial-toolset\"}",
                        "{\"name\":\"search\",\"inputSchema\":{}}")
                .getId();
        UUID route = metaTestDataHelper
                .createTestSuiteOfType(
                        "route-" + UUID.randomUUID(),
                        SuiteType.ROUTE,
                        null,
                        "{\"name\":\"routes/platform/validate-route\"}",
                        null,
                        null)
                .getId();
        seedRun(deployment, 1_000, 1, 10);
        seedRun(mcp, 1_000, 2, 10);
        seedRun(route, 1_000, 3, 10);

        JsonNode content = get("?page=0&size=100").path("content");

        JsonNode deploymentItem = content.get(0);
        assertThat(deploymentItem.path("suiteType").asString()).isEqualTo("DEPLOYMENT");
        assertThat(deploymentItem.path("evaluatee").path("id").asString()).isEqualTo("gpt-4o");
        assertThat(deploymentItem.path("evaluatee").path("name").asString()).isEqualTo("GPT-4o");
        assertThat(deploymentItem.path("evaluatee").path("type").asString()).isEqualTo("dial-model");
        assertThat(deploymentItem.path("evaluatee").has("toolName")).isFalse();
        assertThat(deploymentItem.path("evaluatee").propertyNames()).containsExactlyInAnyOrder("id", "name", "type");
        JsonNode mcpEvaluatee = content.get(1).path("evaluatee");
        assertThat(content.get(1).path("suiteType").asString()).isEqualTo("MCP_TOOL");
        assertThat(mcpEvaluatee.path("id").asString()).isEqualTo("docs-toolset");
        assertThat(mcpEvaluatee.path("name").asString()).isEqualTo("Docs toolset");
        assertThat(mcpEvaluatee.path("type").asString()).isEqualTo("dial-toolset");
        assertThat(mcpEvaluatee.path("toolName").asString()).isEqualTo("search");
        JsonNode routeEvaluatee = content.get(2).path("evaluatee");
        assertThat(routeEvaluatee.path("name").asString()).isEqualTo("routes/platform/validate-route");
        assertThat(routeEvaluatee.has("id")).isFalse();
        assertThat(routeEvaluatee.has("type")).isFalse();
        assertThat(routeEvaluatee.has("toolName")).isFalse();
    }

    @Test
    @DisplayName("a suite renamed after its runs shows the new name")
    void renamedSuite() {
        UUID suite = newSuite("old-name");
        seedRun(suite, 1_000, 1, 2);
        metaTestDataHelper.renameSuite(suite, "New name");

        assertThat(get("?page=0&size=100")
                        .path("content")
                        .get(0)
                        .path("suiteName")
                        .asString())
                .isEqualTo("New name");
    }

    @Test
    @DisplayName("filter suiteType eq MCP_TOOL lists only MCP suites, worst first, with total 2")
    void filterBySuiteType() {
        UUID plain = newSuite("plain");
        seedRun(plain, 1_000, 1, 10);
        List<UUID> mcpSuites = new ArrayList<>();
        int[] passed = {8, 3};
        for (int p : passed) {
            UUID mcp = metaTestDataHelper
                    .createTestSuiteOfType(
                            "mcp-" + UUID.randomUUID(),
                            SuiteType.MCP_TOOL,
                            null,
                            null,
                            "{\"id\":\"t\"}",
                            "{\"name\":\"x\",\"inputSchema\":{}}")
                    .getId();
            mcpSuites.add(mcp);
            seedRun(mcp, 1_000, p, 10);
        }

        JsonNode page = get("?page=0&size=100&filter=suiteType:eq:MCP_TOOL");

        assertThat(suiteIds(page))
                .containsExactly(mcpSuites.get(1).toString(), mcpSuites.get(0).toString());
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("content").get(0).path("latestPassRate").asDouble())
                .isCloseTo(0.3, within(EPSILON));
    }

    @Test
    @DisplayName("a filter matching no suite returns an empty page with total 0")
    void emptyFilterResult() {
        seedRun(newSuite("any"), 1_000, 1, 2);

        JsonNode page = get("?page=0&size=100&filter=name:eq:no-such-suite-" + UUID.randomUUID());

        assertThat(page.path("content")).isEmpty();
        assertThat(page.path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("an unknown filter field is rejected with 400")
    void invalidFilter() {
        assertThat(status("?page=0&size=10&filter=noSuchField:eq:x")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("no pass-count rows: suites exist in meta but the page is empty with total 0 (no filter)")
    void noSuiteQualifies() {
        newSuite("no-runs");

        JsonNode page = get("?page=0&size=100");

        assertThat(page.path("content")).isEmpty();
        assertThat(page.path("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("equal run creation time: the run with the larger id is the latest")
    void sameTimestampLargerRunIdIsLatest() {
        UUID suite = newSuite("tie-run");
        seedRunWithId(UUID.fromString("00000000-0000-0000-0000-000000000002"), suite, 1_000, 10, 10);
        seedRunWithId(UUID.fromString("00000000-0000-0000-0000-000000000001"), suite, 1_000, 0, 10);

        JsonNode item = get("?page=0&size=100").path("content").get(0);

        assertThat(item.path("latestPassRate").asDouble()).isEqualTo(1.0);
        assertThat(item.path("previousPassRate").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("equal latest rate: the suite whose latest run is newer comes first")
    void equalRateNewerRunFirst() {
        UUID older = newSuite("older");
        UUID newer = newSuite("newer");
        seedRun(older, 1_000, 1, 2);
        seedRun(newer, 2_000, 1, 2);

        assertThat(suiteIds(get("?page=0&size=100"))).containsExactly(newer.toString(), older.toString());
    }

    @Test
    @DisplayName("equal latest rate and run time: suites are ordered by suite id ascending")
    void equalRateAndTimeOrderedBySuiteId() {
        UUID first = newSuite("tie-a");
        UUID second = newSuite("tie-b");
        seedRun(first, 1_000, 1, 2);
        seedRun(second, 1_000, 1, 2);
        List<String> expected =
                List.of(first.toString(), second.toString()).stream().sorted().toList();

        assertThat(suiteIds(get("?page=0&size=100"))).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("an operator the field does not allow is rejected with 400")
    void disallowedOperator() {
        assertThat(status("?page=0&size=10&filter=suiteType:co:MCP")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("OpenAPI spec carries the run-trends examples and described page, size and filter parameters")
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
        assertThat(examples.path("full").path("value").path("content").get(0).has("delta"))
                .isTrue();

        // Text only OpenApiQueryParamCustomizer generates (the controller's own descriptions lack it).
        assertThat(parameterDescription(operation, "filter"))
                .contains("Format: `field:operator:value`")
                .contains("| suiteType |");
        assertThat(parameterDescription(operation, "page")).contains("0-indexed page number");
        assertThat(parameterDescription(operation, "size")).contains("max:");
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
