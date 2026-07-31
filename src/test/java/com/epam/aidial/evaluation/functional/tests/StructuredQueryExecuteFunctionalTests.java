package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.experimental.query.service.dto.StructuredQueryResultDto;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end coverage of the unified execute endpoint ({@code POST /api/v1/queries/execute}): the
 * same wire contract drives {@code test_suites} (meta) and {@code eval_summaries} (analytics),
 * routed by the {@code entity} field.
 */
@DisplayName("Structured Query Execute (/api/v1/queries/execute) Tests")
public abstract class StructuredQueryExecuteFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricScoreService metricScoreService;

    private String executeUrl() {
        return baseUrl() + "/api/v1/queries/execute";
    }

    private ResponseEntity<StructuredQueryResultDto> post(String json) {
        return restTemplate.postForEntity(executeUrl(), jsonEntity(json), StructuredQueryResultDto.class);
    }

    @Test
    @DisplayName("executes a test_suites row query routed to the meta datasource")
    void executesTestSuitesRowQuery() {
        String name = "sq-exec-" + UUID.randomUUID();
        TestSuite target = metaTestDataHelper.createTestSuite(name);
        metaTestDataHelper.createTestSuite("sq-exec-" + UUID.randomUUID());

        String json = """
                {
                  "entity": "test_suites",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "name" },
                      { "type": "value", "value_type": "string", "value": "%s" } ] },
                  "select": [
                      { "expr": { "type": "field", "name": "id" } },
                      { "expr": { "type": "field", "name": "name" } } ],
                  "page": { "type": "offset", "offset": 0, "limit": 10, "include_total": true }
                }
                """.formatted(name);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.rows()).hasSize(1);
        assertThat(body.rows().get(0).get("id")).isEqualTo(target.getId().toString());
        assertThat(body.rows().get(0).get("name")).isEqualTo(name);
        assertThat(body.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("executes an eval_summaries aggregate query routed to the analytics datasource")
    void executesEvalSummariesAggregateQuery() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-b", ExecutionStatus.SUCCESS.name(), 200L, 2_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-c", ExecutionStatus.FAILED.name(), 300L, 3_000L);

        String json = """
                {
                  "entity": "eval_summaries",
                  "mode": "aggregate",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                  "group_by": ["execution_status"],
                  "select": [
                      { "expr": { "type": "field", "name": "execution_status" } },
                      { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "total" } ],
                  "sort": [ { "field": "execution_status", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 10 }
                }
                """.formatted(runId);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        // FAILED (1) then SUCCESS (2), ordered by execution status ascending.
        assertThat(body.rows()).hasSize(2);
        assertThat(body.rows().get(0).get("execution_status")).isEqualTo(ExecutionStatus.FAILED.name());
        assertThat(((Number) body.rows().get(0).get("total")).intValue()).isEqualTo(1);
        assertThat(body.rows().get(1).get("execution_status")).isEqualTo(ExecutionStatus.SUCCESS.name());
        assertThat(((Number) body.rows().get(1).get("total")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("returns JSONB columns as nested JSON, not escaped strings")
    void returnsJsonbColumnsAsJson() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, UUID.randomUUID(), "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);

        String json = """
                {
                  "entity": "eval_summaries",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                  "select": [
                      { "expr": { "type": "field", "name": "test_case_data" } },
                      { "expr": { "type": "field", "name": "metric_values" } } ],
                  "page": { "type": "offset", "offset": 0, "limit": 10 }
                }
                """.formatted(runId);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.rows()).hasSize(1);
        Map<String, Object> row = body.rows().get(0);
        // Parsed JSON objects deserialize to Map on the client; a raw escaped string would be a String.
        assertThat(row.get("test_case_data")).isInstanceOf(Map.class);
        assertThat(row.get("metric_values")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("projects a flattened metric: field via the execute endpoint")
    void projectsFlattenedMetricFieldViaEndpoint() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                UUID.randomUUID(),
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{\"question\":\"q1\"}",
                "{\"Exact Match1\":{\"exact_match\":1}}");

        String json = """
                {
                  "entity": "eval_summaries",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                  "select": [
                      { "expr": { "type": "field", "name": "data::question" } },
                      { "expr": { "type": "field", "name": "metric::Exact Match1::exact_match" } } ],
                  "page": { "type": "offset", "offset": 0, "limit": 10 }
                }
                """.formatted(runId);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.rows()).hasSize(1);
        Map<String, Object> row = body.rows().get(0);
        assertThat(row.get("data::question")).isEqualTo("q1");
        assertThat(((Number) row.get("metric::Exact Match1::exact_match")).intValue())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("groups by a width_bucket select alias over a metric field and executes against analytics")
    void groupsByWidthBucketAliasOverMetricField() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Accuracy\":{\"score\":0.1}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-b",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Accuracy\":{\"score\":0.15}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-c",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Accuracy\":{\"score\":0.9}}");

        // Group by the computed "bucket" select alias (a width_bucket over a JSONB metric field): the
        // GROUP BY must reference the output column, not re-inline the expression (which would emit
        // fresh JSONB-key bind parameters and trip "must appear in the GROUP BY clause").
        String json = """
                {
                  "entity": "eval_summaries",
                  "mode": "aggregate",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                  "group_by": ["bucket"],
                  "select": [
                      { "expr": { "type": "fn", "name": "width_bucket", "args": [
                          { "type": "field", "name": "metric::Accuracy::score" },
                          { "type": "value", "value_type": "decimal", "value": "0" },
                          { "type": "value", "value_type": "decimal", "value": "1" },
                          { "type": "value", "value_type": "integer", "value": "5" } ] }, "as": "bucket" },
                      { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "cnt" } ],
                  "sort": [ { "field": "bucket", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 25 }
                }
                """.formatted(runId);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        // Five equal-width buckets over [0, 1): scores 0.1 and 0.15 land in bucket 1, score 0.9 in bucket 5.
        // Sorting by the computed "bucket" alias must also reference the output column (not re-inline the
        // expression), so rows come back ordered: bucket 1 (count 2) then bucket 5 (count 1).
        assertThat(body.rows()).hasSize(2);
        assertThat(((Number) body.rows().get(0).get("bucket")).intValue()).isEqualTo(1);
        assertThat(((Number) body.rows().get(0).get("cnt")).intValue()).isEqualTo(2);
        assertThat(((Number) body.rows().get(1).get("bucket")).intValue()).isEqualTo(5);
        assertThat(((Number) body.rows().get(1).get("cnt")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns HTTP 400 (not 500) when aggregating a non-numeric JSONB field")
    void rejectsAggregateOnNonNumericJsonbFieldViaEndpoint() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, UUID.randomUUID(), "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);

        String json = """
                {
                  "entity": "eval_summaries",
                  "mode": "aggregate",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                  "group_by": ["test_suite_run_id"],
                  "select": [
                      { "expr": { "type": "fn", "name": "avg", "args": [
                          { "type": "field", "name": "metricInfo::Regex Match1" } ] }, "as": "avg" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 25, "include_total": true }
                }
                """.formatted(runId);

        ResponseEntity<String> response = restTemplate.postForEntity(executeUrl(), jsonEntity(json), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("returns every metric-score aggregation for the latest N runs via a JSON subquery `in`")
    void returnsAllMetricScoresForLatestRunsViaSubquery() {
        UUID suiteId = UUID.randomUUID();
        UUID olderRun = UUID.randomUUID();
        UUID newerRun = UUID.randomUUID();
        // Two runs of the same suite, each a computation with all six score names; newerRun computed later.
        seedAllScores(suiteId, olderRun, UUID.randomUUID(), 1_000L);
        seedAllScores(suiteId, newerRun, UUID.randomUUID(), 2_000L);
        // A third, older run that must be excluded by `limit: 2`.
        seedAllScores(suiteId, UUID.randomUUID(), UUID.randomUUID(), 500L);

        String json = """
                {
                  "entity": "metric_score_results",
                  "mode": "row",
                  "filter": { "op": "in", "args": [
                      { "type": "field", "name": "test_suite_run_id" },
                      { "type": "subquery", "query": {
                          "entity": "metric_score_results",
                          "mode": "aggregate",
                          "filter": { "op": "eq", "args": [
                              { "type": "field", "name": "test_suite_id" },
                              { "type": "value", "value_type": "uuid", "value": "%s" } ] },
                          "select": [
                              { "expr": { "type": "field", "name": "test_suite_run_id" }, "as": "test_suite_run_id" },
                              { "expr": { "type": "fn", "name": "max", "args": [
                                  { "type": "field", "name": "computed_at_ms" } ] }, "as": "recency" } ],
                          "group_by": ["test_suite_run_id"],
                          "sort": [ { "field": "recency", "dir": "desc" } ],
                          "page": { "type": "offset", "offset": 0, "limit": 2 }
                      } } ] },
                  "select": [
                      { "expr": { "type": "field", "name": "test_suite_run_id" } },
                      { "expr": { "type": "field", "name": "metric_score_name" } },
                      { "expr": { "type": "field", "name": "metric_name" } },
                      { "expr": { "type": "field", "name": "value" } } ],
                  "sort": [ { "field": "computed_at_ms", "dir": "desc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 100 }
                }
                """.formatted(suiteId);

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        // 6 score names x the latest 2 runs = 12 rows; the oldest run is excluded.
        assertThat(body.rows()).hasSize(12);
        assertThat(body.rows())
                .extracting(row -> row.get("test_suite_run_id"))
                .containsOnly(olderRun.toString(), newerRun.toString());
        assertThat(body.rows())
                .extracting(row -> (String) row.get("metric_score_name"))
                .containsExactlyInAnyOrder(
                        "AVG", "MAX", "MIN", "P10", "P90", "overall", "AVG", "MAX", "MIN", "P10", "P90", "overall");
    }

    /** Persists all six metric-score aggregations for one run/computation of a suite. */
    private void seedAllScores(UUID suiteId, UUID runId, UUID computationId, long computedAtMs) {
        final List<String> perMetric = List.of("AVG", "MAX", "MIN", "P10", "P90");
        final List<MetricScoreResult> results = new ArrayList<>();
        for (final String scoreName : perMetric) {
            results.add(score(suiteId, runId, computationId, scoreName, "Exact Match.exact_match", 1.0, computedAtMs));
        }
        results.add(score(suiteId, runId, computationId, "overall", "overall", 1.0, computedAtMs));
        metricScoreService.saveAll(results);
    }

    private static MetricScoreResult score(
            UUID suiteId, UUID runId, UUID computationId, String scoreName, String metricName, double value, long ms) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .computedAtMs(ms)
                .build();
    }

    @Test
    @DisplayName("rejects a query for an entity that is not queryable with HTTP 400")
    void rejectsUnsupportedEntity() {
        String json = """
                {
                  "entity": "datasets",
                  "mode": "row",
                  "page": { "type": "offset", "offset": 0, "limit": 10 }
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(executeUrl(), jsonEntity(json), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("filters test_suites by deployment_ref::name via the execute endpoint")
    void executesTestSuitesByDeploymentRefName() {
        String name = "sq-depref-exec-" + UUID.randomUUID();
        String deploymentRefJson =
                "{\"id\":\"exec-app-id\",\"name\":\"Exec App\",\"version\":\"2.0\",\"type\":\"dial-application\"}";
        TestSuite target = metaTestDataHelper.createTestSuiteWithDeploymentRef(name, deploymentRefJson);
        metaTestDataHelper.createTestSuite("sq-depref-exec-other-" + UUID.randomUUID());

        String json = """
                {
                  "entity": "test_suites",
                  "mode": "row",
                  "filter": { "op": "eq", "args": [
                      { "type": "field", "name": "deployment_ref::name" },
                      { "type": "value", "value_type": "string", "value": "Exec App" } ] },
                  "select": [
                      { "expr": { "type": "field", "name": "id" } },
                      { "expr": { "type": "field", "name": "deployment_ref::name" } } ],
                  "page": { "type": "offset", "offset": 0, "limit": 10, "include_total": true }
                }
                """;

        ResponseEntity<StructuredQueryResultDto> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StructuredQueryResultDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.rows()).hasSize(1);
        assertThat(body.rows().get(0).get("id")).isEqualTo(target.getId().toString());
        assertThat(body.rows().get(0).get("deployment_ref::name")).isEqualTo("Exec App");
    }
}
