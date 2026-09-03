package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QueryEntitySchemaDto;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Query Schema Discovery (/api/v1/queries) Tests")
public abstract class QuerySchemaDiscoveryFunctionalTests extends BaseFunctionalTest {

    private static final String SNAPSHOT_JSON = """
            {"snapshotVersion":"2","suiteType":"DEPLOYMENT",
             "testCaseSchema":[{"name":"question","type":"STRING","required":true},
                               {"name":"expectedScore","type":"NUMBER","required":false}],
             "responseColumns":[{"name":"answer","expression":"$.answer","type":"STRING"}]}
            """;

    private static final String ACCURACY_OUTPUT_SCHEMA = """
            {"properties": {"score": {"type": "number"}, "explanation": {"type": "string"}}}
            """;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private String queriesUrl(String path) {
        return baseUrl() + "/api/v1/queries" + path;
    }

    /** Seeds a suite + run with a current-version snapshot and one metric snapshot; returns the run. */
    private TestSuiteRun seedRunWithSnapshot() {
        TestSuite suite = metaTestDataHelper.createTestSuite("query-schema-suite-" + UUID.randomUUID());
        TestSuiteRun run = metaTestDataHelper.createLegacyTestSuiteRun(suite.getId());
        metaTestDataHelper.setRunSuiteSnapshot(run.getId(), SNAPSHOT_JSON);
        analyticsTestDataHelper.createRunMetricSnapshot(
                run.getId(), UUID.randomUUID(), "Accuracy", ACCURACY_OUTPUT_SCHEMA, 1_000L);
        return run;
    }

    @Test
    @DisplayName("lists queryable entities with complexity and schema id field")
    void shouldListQueryableEntities() {
        ResponseEntity<List<QueryEntityDto>> response = restTemplate.exchange(
                queriesUrl("/entities"), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsExactly(
                        new QueryEntityDto("eval_summaries", true, "test_suite_run_id"),
                        new QueryEntityDto("metric_score_results", false, null),
                        new QueryEntityDto("test_cases", true, "dataset_id"),
                        new QueryEntityDto("test_suites", false, null));
    }

    @Test
    @DisplayName("returns the flat base schema of the simple test_suites entity")
    void shouldReturnTestSuitesBaseSchema() {
        ResponseEntity<QueryEntitySchemaDto> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/test_suites"), QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.entity()).isEqualTo("test_suites");
        assertThat(schema.complex()).isFalse();
        assertThat(schema.schemaIdField()).isNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("name", QueryFieldType.STRING, "name"),
                        new QuerySchemaFieldDto("response_columns", QueryFieldType.ARRAY, "response_columns"));
    }

    @Test
    @DisplayName("returns the eval_summaries base schema with JSONB fields listed as-is")
    void shouldReturnEvalSummariesBaseSchema() {
        ResponseEntity<QueryEntitySchemaDto> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/eval_summaries"), QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.entity()).isEqualTo("eval_summaries");
        assertThat(schema.complex()).isTrue();
        assertThat(schema.schemaIdField()).isEqualTo("test_suite_run_id");
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("test_case_data", QueryFieldType.OBJECT, "test_case_data"),
                        new QuerySchemaFieldDto("metric_values", QueryFieldType.OBJECT, "metric_values"),
                        // score/passed come from the joined test_case_eval_scores table, not a column of
                        // test_case_eval_summaries itself — must be advertised explicitly (see
                        // EvalSummariesSchemaProvider) to match what PostgresEvalSummaryEntityResolver
                        // actually accepts as queryable fields.
                        new QuerySchemaFieldDto("score", QueryFieldType.DECIMAL, "score"),
                        new QuerySchemaFieldDto("passed", QueryFieldType.BOOLEAN, "passed"));
    }

    @Test
    @DisplayName("returns the detailed eval_summaries schema flattened from the run snapshot via run id")
    void shouldReturnDetailedEvalSummariesSchemaFromRunSnapshot() {
        TestSuiteRun run = seedRunWithSnapshot();

        ResponseEntity<QueryEntitySchemaDto> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_run_id=" + run.getId()),
                QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("test_suite_run_id", QueryFieldType.UUID, "test_suite_run_id"),
                        new QuerySchemaFieldDto("data::question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("data::expectedScore", QueryFieldType.DECIMAL, "test_case_data"),
                        new QuerySchemaFieldDto("response::answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric::Accuracy::score", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto(
                                "metric::Accuracy::explanation", QueryFieldType.DECIMAL, "metric_values"),
                        new QuerySchemaFieldDto("metricInfo::Accuracy", QueryFieldType.OBJECT, "metric_infos"),
                        // score/passed are not flattenable JSONB fields, so the detailed schema keeps them
                        // as-is from the base schema, same as any other plain column.
                        new QuerySchemaFieldDto("score", QueryFieldType.DECIMAL, "score"),
                        new QuerySchemaFieldDto("passed", QueryFieldType.BOOLEAN, "passed"))
                .noneMatch(field -> field.name().equals("test_case_data"))
                .noneMatch(field -> field.name().equals("metric_values"))
                .noneMatch(field -> field.name().equals("metric_infos"));
    }

    @Test
    @DisplayName("resolves the suite's latest run when the detailed schema is requested by test_suite_id")
    void shouldReturnDetailedEvalSummariesSchemaFromLatestRun() {
        TestSuiteRun run = seedRunWithSnapshot();

        ResponseEntity<QueryEntitySchemaDto> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_id=" + run.getTestSuiteId()),
                QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("data::question", QueryFieldType.STRING, "test_case_data"),
                        new QuerySchemaFieldDto("response::answer", QueryFieldType.STRING, "extracted_columns"),
                        new QuerySchemaFieldDto("metric::Accuracy::score", QueryFieldType.DECIMAL, "metric_values"));
    }

    @Test
    @DisplayName("returns 404 for an unknown entity name")
    void shouldReturn404_whenEntityUnknown() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/unknown_entity"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("returns 400 when a detailed schema is requested for a simple entity")
    void shouldReturn400_whenDetailedSchemaRequestedForSimpleEntity() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/test_suites/detailed?test_suite_run_id=" + UUID.randomUUID()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("returns 400 when the detailed schema run has no usable snapshot (legacy run)")
    void shouldReturn400_whenRunHasNoSnapshot() {
        TestSuite suite = metaTestDataHelper.createTestSuite("query-schema-legacy-" + UUID.randomUUID());
        TestSuiteRun run = metaTestDataHelper.createLegacyTestSuiteRun(suite.getId());

        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_run_id=" + run.getId()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("returns 404 when the detailed schema run does not exist")
    void shouldReturn404_whenRunMissing() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_run_id=" + UUID.randomUUID()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("returns 400 when the detailed schema id is not a UUID")
    void shouldReturn400_whenRunIdMalformed() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                queriesUrl("/entities/schema/eval_summaries/detailed?test_suite_run_id=not-a-uuid"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("test_suites base schema includes deployment_ref sub-fields and retains the opaque object binding")
    void shouldReturnTestSuitesBaseSchemaWithDeploymentRefSubFields() {
        ResponseEntity<QueryEntitySchemaDto> response =
                restTemplate.getForEntity(queriesUrl("/entities/schema/test_suites"), QueryEntitySchemaDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        QueryEntitySchemaDto schema = response.getBody();
        assertThat(schema).isNotNull();
        assertThat(schema.fields())
                .contains(
                        new QuerySchemaFieldDto("deployment_ref", QueryFieldType.OBJECT, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::id", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::name", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("deployment_ref::version", QueryFieldType.STRING, "deployment_ref"),
                        new QuerySchemaFieldDto("mcp_deployment_ref", QueryFieldType.OBJECT, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto("mcp_deployment_ref::id", QueryFieldType.STRING, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::name", QueryFieldType.STRING, "mcp_deployment_ref"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::type", QueryFieldType.STRING, "mcp_deployment_ref"));
    }
}
